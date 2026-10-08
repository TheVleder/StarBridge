package org.starbridge.imaging

import kotlin.math.max

/** Everything measured about one frame before deciding what to do with it. */
data class FrameMetrics(
    val stars: Int,
    /** Reference stars that should be visible in this frame and were found (0..1). */
    val matchedFraction: Double,
    val fwhm: Double,
    val hfr: Double,
    val elongation: Double,
    val backgroundLevel: Double,
    val backgroundSigma: Double,
    /** Photometric transparency vs the reference (1 = same). */
    val transparency: Double,
    val registered: Boolean,
    val registrationRms: Double,
    val inliers: Int,
    val gyroRms: Double?,
    val mountMoving: Boolean,
)

enum class Verdict { ACCEPT, DOWNWEIGHT, REJECT }

enum class QualityMode { CONSERVATIVE, NORMAL, OFF }

data class Decision(val verdict: Verdict, val weight: Double, val reasons: List<String>)

/**
 * Quality control. Golden rule: a frame that is fainter, hazier, a bit softer or slightly
 * elongated is NOT bad. Weighted by the quality of the star *cores* (point-source SNR), it
 * always adds signal. **Conservative** (default) rejects only frames that corrupt the stack:
 * streaks, extreme defocus, bumps, patchy cloud (missing signal), failed registration.
 * **Normal** also rejects moderately soft/elongated frames, for the sharpest possible image.
 */
class QualityGate(var mode: QualityMode = QualityMode.CONSERVATIVE) {
    private val history = ArrayList<FrameMetrics>()
    private var refFwhm = Double.NaN
    // Weighted-mean noise bookkeeping for the SNR arbiter: S1 = Σw, S2 = Σ w² σ².
    private var s1 = 0.0
    private var s2 = 0.0

    val accepted: Int get() = history.size

    private fun baseline(f: (FrameMetrics) -> Double): Pair<Double, Double>? {
        val v = history.takeLast(20).map(f).filter { !it.isNaN() }
        if (v.size < 3) return null
        return Stats.median(v) to max(Stats.madSigma(v), 1e-6)
    }

    private val threshold get() = when (mode) {
        QualityMode.CONSERVATIVE -> 4.5
        QualityMode.NORMAL -> 3.5
        QualityMode.OFF -> Double.POSITIVE_INFINITY
    }

    fun decide(m: FrameMetrics): Decision {
        val reasons = ArrayList<String>()
        val harmful = ArrayList<String>()
        // Things that make a frame impossible or dangerous to stack, whatever the mode.
        if (!m.registered) return Decision(Verdict.REJECT, 0.0, listOf("No se pudo alinear"))
        if (m.mountMoving) return Decision(Verdict.REJECT, 0.0, listOf("El telescopio se movía"))

        val early = history.size < 5
        val gyroLimit = gyroLimit()
        if (m.gyroRms != null && gyroLimit != null && m.gyroRms > gyroLimit) harmful += "Golpe o vibración"

        // Corrupting whatever the session looks like: stars turned into streaks.
        if (m.elongation > STREAK_ELONGATION) harmful += "Estrellas convertidas en trazos"
        if (!early) {
            baseline { it.elongation }?.let { (med, mad) ->
                val z = (m.elongation - med) / mad
                when {
                    mode == QualityMode.NORMAL && z > threshold && m.elongation > 1.25 -> harmful += "Estrellas alargadas (viento o seguimiento)"
                    z > 2.5 -> reasons += "Estrellas algo alargadas"
                }
            }
            baseline { it.hfr }?.let { (med, mad) ->
                val z = (m.hfr - med) / mad
                when {
                    m.hfr > med * EXTREME_DEFOCUS -> harmful += "Muy desenfocada"
                    mode == QualityMode.NORMAL && z > threshold && m.hfr > med * 1.3 -> harmful += "Desenfocada o turbulencia fuerte"
                    z > 2.5 -> reasons += "Algo menos nítida"
                }
            }
            // Most of the stars that should be visible are missing: dense (patchy) cloud. The
            // signal is missing behind the cloud, not just noisier, so it must not be added.
            if (m.matchedFraction < 0.25) harmful += PATCHY_CLOUD
            else if (m.transparency < 0.15) harmful += "Nube densa"
            else if (m.transparency < 0.7) reasons += "Neblina o menos transparencia"
            baseline { it.backgroundSigma }?.let { (med, mad) ->
                if ((m.backgroundSigma - med) / mad > 2.5) reasons += "Fondo más ruidoso"
            }
        }

        val weight = weightOf(m)
        val verdict = when {
            harmful.isNotEmpty() && mode != QualityMode.OFF -> Verdict.REJECT
            reasons.isNotEmpty() || harmful.isNotEmpty() -> Verdict.DOWNWEIGHT
            else -> Verdict.ACCEPT
        }
        // Final arbiter: a sharp, well-registered frame that would improve the SNR is never
        // rejected for a photometric reason alone (clouds/haze).
        // (Not for patchy cloud: there the noise looks fine but signal is missing.)
        if (verdict == Verdict.REJECT && harmful.all { it == "Nube densa" } && sharp(m) && improvesSnr(weight, m)) {
            return Decision(Verdict.DOWNWEIGHT, weight, harmful + reasons + "Aceptada: aun así mejora la señal")
        }
        return Decision(verdict, if (verdict == Verdict.REJECT) 0.0 else weight, harmful + reasons)
    }

    /** Must be called after a frame has been stacked with weight [w]. */
    fun commit(m: FrameMetrics, w: Double) {
        history.add(m)
        if (refFwhm.isNaN() && !m.fwhm.isNaN()) refFwhm = m.fwhm
        val sigma = m.backgroundSigma / m.transparency.coerceAtLeast(0.05)
        s1 += w
        s2 += w * w * sigma * sigma
    }

    fun reset() {
        history.clear(); refFwhm = Double.NaN; s1 = 0.0; s2 = 0.0
    }

    /**
     * Point-source optimal weight: w ∝ peak / noise². After normalization the star flux is the
     * same in every frame, so the peak ∝ 1/(a·b) (the PSF axes) and the noise ∝ σ/T:
     *   w = (T²/σ²) · (a·b)_ref / (a·b),  with a·b = FWHM² · 2e/(1+e²), e = elongation.
     * With these weights adding a softer frame never lowers the peak SNR of the stack.
     */
    fun weightOf(m: FrameMetrics): Double {
        val ref = history.firstOrNull()
        val refSigma = ref?.backgroundSigma ?: m.backgroundSigma
        val t = m.transparency.coerceIn(0.05, 1.5)
        val inv = (t * t) * (refSigma * refSigma) / (m.backgroundSigma * m.backgroundSigma).coerceAtLeast(1e-9)
        fun area(fwhm: Double, e: Double): Double {
            val el = if (e.isNaN() || e < 1) 1.0 else e
            return fwhm * fwhm * 2 * el / (1 + el * el)
        }
        val refE = ref?.elongation ?: m.elongation
        val sharp = if (refFwhm.isNaN() || m.fwhm.isNaN()) 1.0 else (area(refFwhm, refE) / area(m.fwhm, m.elongation)).coerceIn(0.05, 1.5)
        return (inv * sharp).coerceIn(0.01, 4.0)
    }

    private fun sharp(m: FrameMetrics): Boolean {
        val b = baseline { it.hfr } ?: return true
        return m.hfr <= b.first * 1.2
    }

    private fun improvesSnr(w: Double, m: FrameMetrics): Boolean {
        if (s1 <= 0) return true
        val sigma = m.backgroundSigma / m.transparency.coerceAtLeast(0.05)
        val before = s2 / (s1 * s1)
        val after = (s2 + w * w * sigma * sigma) / ((s1 + w) * (s1 + w))
        return after < before
    }

    companion object {
        const val PATCHY_CLOUD = "Nube densa: faltan la mayoría de las estrellas"
        /** Elongation above which stars are streaks (bump, mount jump, wind gust). */
        const val STREAK_ELONGATION = 2.6
        /** Half-flux radius above which the frame is hopelessly out of focus (× session median). */
        const val EXTREME_DEFOCUS = 2.2
    }

    /** Gyro limit learnt from the first frames: well above the normal tracking vibration. */
    private fun gyroLimit(): Double? {
        val v = history.takeLast(20).mapNotNull { it.gyroRms }
        if (v.size < 3) return 0.5
        val med = Stats.median(v)
        val mad = Stats.madSigma(v)
        return max(0.3, med + 6 * mad)
    }

}
