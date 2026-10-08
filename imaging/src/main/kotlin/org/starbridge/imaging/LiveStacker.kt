package org.starbridge.imaging

import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max

data class StackConfig(
    val conversion: Conversion = Conversion(binning = 1, color = false),
    val kappa: Float = 3.0f,
    val qualityMode: QualityMode = QualityMode.CONSERVATIVE,
    /** Detect the afocal eyepiece circle and ignore everything outside it. */
    val eyepieceMask: Boolean = true,
)

/** Finds a master dark matching a frame (library on disk in the app). */
fun interface DarkProvider {
    fun find(exposureSec: Double, iso: Int, width: Int, height: Int, channels: Int): MasterDark?
}

/** Keeps calibrated frames that were rejected, so the user can "Recuperar" them. */
interface FrameStore {
    fun save(id: Int, frame: Frame)
    fun load(id: Int): Frame?
    fun delete(id: Int)
}

class MemoryFrameStore(private val max: Int = 20) : FrameStore {
    private val map = LinkedHashMap<Int, Frame>()
    override fun save(id: Int, frame: Frame) {
        map[id] = frame
        while (map.size > max) map.remove(map.keys.first())
    }
    override fun load(id: Int) = map[id]
    override fun delete(id: Int) { map.remove(id) }
}

/** What happened to one frame (logged, shown in the session graph and the rejected list). */
data class FrameRecord(
    val id: Int,
    val timestampMs: Long,
    val exposureSec: Double,
    val verdict: Verdict,
    val weight: Double,
    val reasons: List<String>,
    val metrics: FrameMetrics?,
    val method: String?,
    val angleDeg: Double?,
    val processingMs: Long,
    /** Stack quality after this frame. */
    val snrGain: Double,
)

/** Live numbers for the UI. */
data class StackStatus(
    val frames: Int,
    val accepted: Int,
    val downweighted: Int,
    val rejected: Int,
    val integrationSec: Double,
    /** σ(single frame) / σ(stack): how much cleaner the stack is. */
    val snrGain: Double,
    /** 2.5·log10(snrGain): how many magnitudes deeper. */
    val magGain: Double,
    val singleFwhm: Double,
    val stackFwhm: Double,
    val lastReasons: List<String>,
    val plateau: Boolean,
    val referenceSet: Boolean,
    val defects: Int,
    val maskRadiusKnown: Boolean,
)

/**
 * The live-stacking pipeline. Not thread-safe: call it from a single worker thread.
 */
class LiveStacker(
    val config: StackConfig,
    private val darks: DarkProvider? = null,
    private val store: FrameStore? = MemoryFrameStore(),
    private val detector: StarDetector = StarDetector(),
) {
    private var stacker: Stacker? = null
    private var registrar: Registrar? = null
    private var reference: Detection? = null
    private var refSingle: Frame? = null
    private var mask: Mask? = null
    private var defects: DefectMap? = null
    private var lastTransform = Similarity.IDENTITY
    private var lastFwhm = 3.0
    private var sigma1 = Double.NaN
    private var warpedSigmaKnown = false
    private var refBgLevels = FloatArray(0)
    private var nextId = 1
    private var lastReasons: List<String> = emptyList()
    private var stackFwhm = Double.NaN
    private val gate = QualityGate(config.qualityMode)
    private val gainHistory = ArrayList<Pair<Long, Double>>()

    /**
     * The first [LEARN_FRAMES] frames are held back until the hot-pixel map has been learnt
     * from them; then they are corrected, the best one becomes the reference and all of them
     * are stacked. Otherwise early hot pixels would leave arcs in the stack.
     */
    private class Held(val id: Int, val wf: WorkingFrame, val det: Detection, val t0: Long)
    private val held = ArrayList<Held>()

    val records = ArrayList<FrameRecord>()
    var lastDetection: Detection? = null
        private set

    fun status(): StackStatus {
        val acc = records.count { it.verdict == Verdict.ACCEPT }
        val down = records.count { it.verdict == Verdict.DOWNWEIGHT }
        val rej = records.count { it.verdict == Verdict.REJECT }
        val gain = records.lastOrNull { it.verdict != Verdict.REJECT }?.snrGain ?: 1.0
        return StackStatus(
            frames = records.size, accepted = acc, downweighted = down, rejected = rej,
            integrationSec = records.filter { it.verdict != Verdict.REJECT }.sumOf { it.exposureSec },
            snrGain = gain, magGain = 2.5 * log10(max(1.0, gain)),
            singleFwhm = reference?.stats?.medianFwhm ?: Double.NaN, stackFwhm = stackFwhm,
            lastReasons = lastReasons, plateau = plateau(), referenceSet = reference != null,
            defects = defects?.size ?: 0, maskRadiusKnown = mask != null,
        )
    }

    fun reset() {
        stacker = null; registrar = null; reference = null; refSingle = null; mask = null
        defects = null; lastTransform = Similarity.IDENTITY; sigma1 = Double.NaN; warpedSigmaKnown = false
        records.clear(); gainHistory.clear(); gate.reset(); stackFwhm = Double.NaN
        lastReasons = emptyList(); lastDetection = null; held.clear(); starMaskCache = null
    }

    /** Calibration shared by stacking, focus and framing: dark, defects, mask. */
    fun calibrate(raw: RawFrame): Pair<WorkingFrame, Mask> {
        val wf = RawConverter.convert(raw, config.conversion)
        val f = wf.frame
        val dark = darks?.find(raw.meta.exposureSec, raw.meta.iso, f.width, f.height, f.channels.size)
        val dm = defects ?: DefectMap(f.width, f.height).also { defects = it }
        if (dark != null) {
            Calibration.subtractDark(f, dark)
            dm.setStatic(dark.defects)
        }
        dm.correct(f)
        val m = mask ?: run {
            val circle = if (config.eyepieceMask) Calibration.eyepieceMask(f.luminance) else null
            (circle ?: Calibration.borderMask(f.width, f.height)).also { mask = it }
        }
        // Keep learning hot pixels all session long (corrected next frame).
        val noise = Stats.robustNoise(f.luminance, m)
        if (!noise.isNaN()) dm.learn(f.luminance, noise.toFloat())
        return wf to m
    }

    /**
     * One frame through the whole pipeline. Returns the records produced: none while the
     * first frames are held back to learn the sensor, several when they are released.
     */
    fun process(raw: RawFrame): List<FrameRecord> {
        val t0 = System.nanoTime()
        val (wf, m) = calibrate(raw)
        val f = wf.frame
        val det = detector.detect(f.luminance, m, wf.unsaturated, lastFwhm)
        lastDetection = det
        if (!det.stats.medianFwhm.isNaN()) lastFwhm = det.stats.medianFwhm.coerceIn(1.5, 12.0)
        val id = nextId++
        if (reference == null) {
            held.add(Held(id, wf, det, t0))
            if (held.size < LEARN_FRAMES) return emptyList()
            return releaseHeld(m)
        }
        return listOf(processCalibrated(id, wf, det, m, t0))
    }

    /** Stacks the frames still held back (e.g. the session ends before [LEARN_FRAMES] frames). */
    fun flush(): List<FrameRecord> {
        val m = mask ?: return emptyList()
        if (reference != null || held.isEmpty()) return emptyList()
        return releaseHeld(m)
    }

    /** Re-corrects the held frames with the learnt defects, picks the best as reference, stacks all. */
    private fun releaseHeld(m: Mask): List<FrameRecord> {
        val out = ArrayList<FrameRecord>()
        val dm = defects
        val redone = held.map { h ->
            dm?.correct(h.wf.frame)
            val d = detector.detect(h.wf.frame.luminance, m, h.wf.unsaturated, lastFwhm)
            Held(h.id, h.wf, d, h.t0)
        }
        held.clear()
        // Best = most stars, then sharpest.
        val best = redone.filter { regStars(it.det).size >= MIN_REFERENCE_STARS }
            .maxWithOrNull(compareBy<Held> { regStars(it.det).size }.thenBy { -(it.det.stats.medianHfr.takeIf { h -> !h.isNaN() } ?: 99.0) })
        if (best == null) {
            // Still no usable frame: report them and keep waiting (keep the newest).
            for (h in redone.dropLast(1)) out.add(record(h.id, h.wf.meta, Verdict.REJECT, 0.0, listOf("Esperando una foto con estrellas para empezar"), null, null, null, h.t0))
            held.add(redone.last())
            return out
        }
        startReference(best, m)
        out.add(record(best.id, best.wf.meta, Verdict.ACCEPT, 1.0, listOf("Foto de referencia"), metricsOf(best.det, best.wf, 1.0, 1.0, true, 0.0, best.det.stars.size), "reference", 0.0, best.t0))
        for (h in redone.sortedBy { it.id }) if (h !== best) out.add(processCalibrated(h.id, h.wf, h.det, m, h.t0))
        return out.sortedBy { it.id }
    }

    private fun startReference(h: Held, m: Mask) {
        val f = h.wf.frame
        reference = h.det
        refSingle = f.copy()
        registrar = Registrar(regStars(h.det))
        val s = Stacker(f.width, f.height, f.channels.size, config.kappa)
        stacker = s
        sigma1 = Stats.robustNoise(f.luminance, starFree(m))
        refBgLevels = FloatArray(f.channels.size) { c -> channelLevel(f.channels[c], m) }
        val metrics = metricsOf(h.det, h.wf, 1.0, 1.0, true, 0.0, h.det.stars.size)
        val w = gate.weightOf(metrics)
        s.add(f, coverageOf(m), w, h.det.background.sigma)
        gate.commit(metrics, w)
    }

    private fun processCalibrated(id: Int, wf: WorkingFrame, det: Detection, m: Mask, t0: Long): FrameRecord {
        val f = wf.frame
        val meta = wf.meta
        val reg = registrar?.register(regStars(det), lastTransform, det.stats.medianFwhm)
        val (transparency, matchedFraction) = if (reg != null) photometry(det, reg.transform, m) else 1.0 to 0.0
        val metrics = metricsOf(det, wf, transparency, matchedFraction, reg != null, reg?.rmsPx ?: Double.NaN, reg?.inliers ?: 0)
        val decision = gate.decide(metrics)
        lastReasons = decision.reasons
        if (decision.verdict == Verdict.REJECT || reg == null) {
            store?.save(id, f)
            return record(id, meta, Verdict.REJECT, 0.0, decision.reasons, metrics, reg?.method, reg?.transform?.angleDeg, t0)
        }
        stackFrame(f, wf.unsaturated, m, reg.transform, transparency, det.background.sigma, decision.weight)
        gate.commit(metrics, decision.weight)
        lastTransform = reg.transform
        return record(id, meta, decision.verdict, decision.weight, decision.reasons, metrics, reg.method, reg.transform.angleDeg, t0)
    }

    /** Adds a previously rejected frame anyway (user "Recuperar"). */
    fun recover(id: Int): FrameRecord? {
        val f = store?.load(id) ?: return null
        val m = mask ?: return null
        val det = detector.detect(f.luminance, m, null, lastFwhm)
        val reg = registrar?.register(regStars(det), lastTransform, det.stats.medianFwhm) ?: return null
        val store = store ?: return null
        val (t, frac) = photometry(det, reg.transform, m)
        val metrics = metricsOf(det, null, t, frac, true, reg.rmsPx, reg.inliers)
        val w = gate.weightOf(metrics)
        stackFrame(f, null, m, reg.transform, t, det.background.sigma, w)
        gate.commit(metrics, w)
        store.delete(id)
        val idx = records.indexOfFirst { it.id == id }
        val rec = FrameRecord(id, records.getOrNull(idx)?.timestampMs ?: 0, records.getOrNull(idx)?.exposureSec ?: 0.0,
            Verdict.DOWNWEIGHT, w, listOf("Recuperada por el usuario"), metrics, reg.method, reg.transform.angleDeg, 0, updateGain())
        if (idx >= 0) records[idx] = rec else records.add(rec)
        return rec
    }

    private fun stackFrame(f: Frame, unsat: Mask?, m: Mask, t: Similarity, transparency: Double, sigma: Float, weight: Double) {
        val s = stacker ?: return
        // Normalization: background to the reference level, flux scaled by 1/transparency.
        val mult = (1.0 / transparency.coerceIn(0.05, 1.5)).toFloat()
        val norm = Frame(f.channels.mapIndexed { c, img ->
            val lvl = channelLevel(img, m)
            Image(img.width, img.height, FloatArray(img.data.size) { i -> mult * (img.data[i] - lvl) + refBgLevels[c] })
        })
        val valid = if (unsat == null) m else Mask(m.width, m.height, ByteArray(m.data.size) { if (m[it] && unsat[it]) 1 else 0 })
        val warped = Warper.warp(norm, t, s.width, s.height, valid)
        // Fair reference for the gain: the noise of a single frame *after* the same resampling
        // as the stack (bilinear interpolation smooths noise a little; don't count that as gain).
        if (!warpedSigmaKnown) {
            val cov = Mask(s.width, s.height, ByteArray(warped.coverage.size) { if (warped.coverage[it] > 0f && m[it]) 1 else 0 })
            val sw = Stats.robustNoise(warped.frame.luminance, starFree(cov)) / mult
            if (!sw.isNaN() && sw > 0) { sigma1 = sw; warpedSigmaKnown = true }
        }
        // The rejection floor is the frame's own noise: that IS the true scatter of a
        // background pixel, so a small-sample σ estimate can never make it cut good samples.
        s.add(warped.frame, warped.coverage, weight, sigma * mult)
    }

    /** Transparency (from matched star fluxes) and the fraction of expected stars found. */
    private fun photometry(det: Detection, t: Similarity, m: Mask): Pair<Double, Double> {
        val ref = reference ?: return 1.0 to 0.0
        val ratios = ArrayList<Double>()
        var matched = 0
        val used = HashSet<Int>()
        for (s in det.all) {
            if (s.saturated) continue
            val x = t.applyX(s.x, s.y)
            val y = t.applyY(s.x, s.y)
            var best = -1
            var bd = 1.5
            for (j in ref.all.indices) {
                val d = hypot(ref.all[j].x - x, ref.all[j].y - y)
                if (d < bd) { bd = d; best = j }
            }
            if (best < 0 || !used.add(best)) continue
            matched++
            val r = ref.all[best]
            if (!r.saturated && r.snr > 20 && s.snr > 10) ratios.add(s.flux / r.flux)
        }
        val transparency = if (ratios.size >= 5) Stats.median(ratios).coerceIn(0.05, 1.5) else 1.0
        // Reference stars that should be visible here: inside the frame and bright enough
        // to be detected after dimming by the transparency.
        val inv = t.inverse()
        val limit = (ref.all.filter { !it.saturated }.minOfOrNull { it.flux } ?: 0.0) * 1.5
        val expected = ref.all.count { r ->
            val fx = inv.applyX(r.x, r.y)
            val fy = inv.applyY(r.x, r.y)
            val xi = fx.toInt(); val yi = fy.toInt()
            xi in 0 until m.width && yi in 0 until m.height && m[xi, yi] && r.flux * transparency > limit
        }
        val frac = if (expected == 0) 1.0 else (matched.toDouble() / expected).coerceAtMost(1.0)
        // With a handful of stars the fraction only says which one the detector caught: no cloud signal.
        return transparency to (if (regStars(ref).size < 5) 1.0 else frac)
    }

    private fun metricsOf(det: Detection, wf: WorkingFrame?, t: Double, frac: Double, registered: Boolean, rms: Double, inliers: Int) =
        FrameMetrics(
            stars = det.stats.count, matchedFraction = frac,
            fwhm = det.stats.medianFwhm, hfr = det.stats.medianHfr, elongation = det.stats.medianElongation,
            backgroundLevel = det.background.level.toDouble(), backgroundSigma = det.background.sigma.toDouble().coerceAtLeast(1e-6),
            transparency = t, registered = registered, registrationRms = rms, inliers = inliers,
            gyroRms = wf?.meta?.gyroRmsDegPerSec, mountMoving = wf?.meta?.mountMoving ?: false,
        )

    private fun channelLevel(img: Image, m: Mask) = Stats.clipped(Stats.sample(img, m, 100_000)).median

    private fun coverageOf(m: Mask) = FloatArray(m.data.size) { if (m[it]) 1f else 0f }

    private fun record(
        id: Int, meta: FrameMeta, v: Verdict, w: Double, reasons: List<String>, metrics: FrameMetrics?,
        method: String?, angle: Double?, t0: Long,
    ): FrameRecord {
        val gain = if (v != Verdict.REJECT) updateGain() else (records.lastOrNull()?.snrGain ?: 1.0)
        val rec = FrameRecord(id, meta.timestampMs, meta.exposureSec, v, w, reasons, metrics, method, angle,
            (System.nanoTime() - t0) / 1_000_000, gain)
        records.add(rec)
        lastReasons = reasons
        return rec
    }

    /** Measures σ(single)/σ(stack) with the robust estimator; stack FWHM every 5 frames. */
    private fun updateGain(): Double {
        val s = stacker ?: return 1.0
        val stack = s.result().luminance
        val m = mask
        val cov = s.coverage()
        val valid = Mask(stack.width, stack.height, ByteArray(cov.size) { if (cov[it] > 0.8f && (m == null || m[it])) 1 else 0 })
        val sigmaN = Stats.robustNoise(stack, starFree(valid))
        val gain = if (sigmaN > 0 && !sigma1.isNaN()) sigma1 / sigmaN else 1.0
        gainHistory.add(System.currentTimeMillis() to gain)
        if (s.frames % 5 == 0 || s.frames <= 2) {
            // Uncovered pixels are NaN: fill them with the background level before measuring.
            val level = Stats.median(Stats.sample(stack, valid, 50_000))
            val filled = Image(stack.width, stack.height, FloatArray(stack.data.size) { i ->
                val v = stack.data[i]
                if (v.isNaN()) level else v
            })
            stackFwhm = detector.detect(filled, valid, null, lastFwhm).stats.medianFwhm
        }
        return gain
    }

    /**
     * [base] minus discs around every star of the reference (radius 3·FWHM + 2): noise must be
     * measured on the background only, or the faint stars rising out of the noise as the
     * stack deepens would be mistaken for noise.
     */
    private var starMaskCache: Mask? = null
    private fun starFree(base: Mask): Mask {
        val ref = reference ?: return base
        val stars = starMaskCache ?: run {
            val sm = Mask.all(base.width, base.height)
            val r = (3 * (ref.stats.medianFwhm.takeIf { !it.isNaN() } ?: 3.0) + 2).toInt()
            for (s in ref.all) {
                val cx = s.x.toInt(); val cy = s.y.toInt()
                for (dy in -r..r) for (dx in -r..r) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until sm.width && y in 0 until sm.height && dx * dx + dy * dy <= r * r) sm.data[y * sm.width + x] = 0
                }
            }
            sm.also { starMaskCache = it }
        }
        return Mask(base.width, base.height, ByteArray(base.data.size) { if (base[it] && stars[it]) 1 else 0 })
    }

    /** Latest stack noise relative to a single frame, measured robustly (for tests and the UI). */
    fun currentGain(): Double = records.lastOrNull { it.verdict != Verdict.REJECT }?.snrGain ?: 1.0

    /** True if the gain improved < 3 % over the last 10 minutes (sky-limited). */
    private fun plateau(): Boolean {
        if (gainHistory.size < 10) return false
        val now = gainHistory.last()
        val past = gainHistory.lastOrNull { now.first - it.first >= 600_000 } ?: return false
        return now.second < past.second * 1.03
    }

    // --- Pictures -------------------------------------------------------------------

    fun renderStack(settings: RenderSettings): Picture? {
        flush()
        val s = stacker ?: return null
        return Renderer.render(s.result(), s.coverage(), mask, settings, s.frames)
    }

    fun renderReference(settings: RenderSettings): Picture? {
        val f = refSingle ?: return null
        return Renderer.render(f, null, mask, settings, 1)
    }

    /** Changes the frame filter from the next frame on (the stack is kept). */
    fun setQualityMode(mode: QualityMode) { gate.mode = mode }

    /** Per-pixel coverage of the stack (for cropping exports), null before the first frame. */
    fun stackCoverage(): FloatArray? = stacker?.coverage()

    /** Usable area (eyepiece circle or frame minus border), null before the first frame. */
    val usableMask: Mask? get() = mask

    /** Linear stack for FITS export. */
    fun stackFrame(): Frame? {
        flush()
        return stacker?.result()
    }

    companion object {
        const val LEARN_FRAMES = 3
        /** Poor fields work too: one star is enough (a phone at the eyepiece often sees one or two). */
        const val MIN_REFERENCE_STARS = 1

        /**
         * Stars to register with: the clean ones, plus the saturated ones when there are few (the
         * bright star of a sparse field is usually saturated, and its centroid is still good).
         */
        fun regStars(det: Detection): List<Star> =
            if (det.stars.size >= 5) det.stars
            else det.stars + det.all.filter { it.saturated && it.peak / it.flux < StarDetector.MAX_SHARPNESS }.sortedByDescending { it.flux }
    }

    /** Single calibrated frame preview (framing / focus), without stacking. */
    fun renderSingle(raw: RawFrame, settings: RenderSettings): Pair<Picture, Detection> {
        val (wf, m) = calibrate(raw)
        val det = detector.detect(wf.frame.luminance, m, wf.unsaturated, lastFwhm)
        lastDetection = det
        return Renderer.render(wf.frame, null, m, settings, 1) to det
    }
}
