package org.starbridge.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Labelled frames: the filter must never throw away a good-but-faint frame, and must
 * catch the harmful ones.
 */
class QualityTest {
    private enum class Kind(val harmful: Boolean) {
        // Useful (must never be rejected): weaker, hazier, softer or slightly elongated.
        GOOD(false), HAZE(false), THIN_CLOUD(false), MILD_WIND(false), SOFT(false),
        // Corrupting (must be rejected): streaks, hopeless defocus, bumps, patchy dense cloud.
        OPAQUE(true), STREAK(true), DEFOCUS(true), BUMP(true)
    }

    private fun shot(sim: SkySimulator, k: Int, kind: Kind, seed: Int) = SkySimulator.Shot(
        transform = sim.sessionTransform(k, 0.05, 0.3), noiseSeed = seed * 1000 + k,
        transparency = when (kind) { Kind.HAZE -> 0.6; Kind.THIN_CLOUD -> 0.35; else -> 1.0 },
        cloudCover = if (kind == Kind.OPAQUE) 0.97 else 0.0,
        elongation = when (kind) { Kind.MILD_WIND -> 1.5; Kind.STREAK -> 5.0; else -> 1.0 },
        fwhm = if (kind == Kind.SOFT) 4.2 else 3.0,
        donutRadius = if (kind == Kind.DEFOCUS) 9.0 else 0.0,
        gyroRms = if (kind == Kind.BUMP) 2.5 else 0.02,
    )

    @Test
    fun neverRejectsGoodFramesAndCatchesHarmfulOnes() {
        val falseRejects = ArrayList<String>()
        var harmful = 0
        var caught = 0
        val missed = ArrayList<String>()
        for (seed in 1..8) {
            val sim = SkySimulator(width = 300, height = 300, seed = 40 + seed, hotPixels = 20)
            val ls = LiveStacker(StackConfig(eyepieceMask = false))
            val plan = List(8) { Kind.GOOD } + listOf(
                Kind.HAZE, Kind.GOOD, Kind.OPAQUE, Kind.MILD_WIND, Kind.THIN_CLOUD, Kind.STREAK, Kind.SOFT,
                Kind.DEFOCUS, Kind.GOOD, Kind.BUMP, Kind.HAZE, Kind.GOOD,
            )
            val kindById = HashMap<Int, Kind>()
            var id = 1
            for ((k, kind) in plan.withIndex()) {
                kindById[id++] = kind
                for (r in ls.process(sim.render(shot(sim, k, kind, seed)))) {
                    val kd = kindById.getValue(r.id)
                    if (kd.harmful) {
                        harmful++
                        if (r.verdict == Verdict.REJECT) caught++ else missed += "seed $seed $kd -> ${r.verdict} ${r.reasons}"
                    } else if (r.verdict == Verdict.REJECT) {
                        falseRejects += "seed $seed $kd -> ${r.reasons} m=${r.metrics}"
                    }
                }
            }
        }
        assertTrue(falseRejects.isEmpty(), "good frames rejected:\n${falseRejects.joinToString("\n")}")
        assertTrue(caught >= 0.95 * harmful, "harmful caught $caught/$harmful; missed:\n${missed.joinToString("\n")}")
    }

    @Test
    fun sparseFieldIsNeverPenalized() {
        // A poor field (25 stars) all session long: nothing must be rejected.
        val sim = SkySimulator(width = 300, height = 300, seed = 9, starCount = 25, hotPixels = 10)
        val ls = LiveStacker(StackConfig(eyepieceMask = false))
        val rejected = ArrayList<String>()
        for (k in 0 until 16) for (r in ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k, 0.05, 0.3), noiseSeed = 70 + k)))) {
            if (r.verdict == Verdict.REJECT) rejected += "${r.id}: ${r.reasons}"
        }
        assertEquals(emptyList(), rejected)
    }

    @Test
    fun filterNeverMakesTheStackWorse() {
        val sim = SkySimulator(width = 300, height = 300, seed = 31, hotPixels = 0)
        fun session(mode: QualityMode): LiveStacker {
            val ls = LiveStacker(StackConfig(eyepieceMask = false, qualityMode = mode))
            for (k in 0 until 24) {
                ls.process(sim.render(SkySimulator.Shot(
                    transform = sim.sessionTransform(k, 0.05, 0.3), noiseSeed = 600 + k,
                    elongation = when (k) { 9, 13 -> 1.6; 17, 21 -> 5.0; else -> 1.0 }, // mild wind and streaks
                    fwhm = if (k == 11 || k == 19) 4.2 else 3.0, // softer frames
                )))
            }
            ls.stackFrame()
            return ls
        }
        fun quality(ls: LiveStacker): Pair<Double, Double> {
            val f = ls.stackFrame()!!.luminance
            val m = Calibration.borderMask(f.width, f.height, 30)
            val det = StarDetector().detect(f, m, null)
            val noise = Stats.robustNoise(f, m)
            val bright = det.stars.filter { it.snr > 20 }
            return Stats.median(bright.map { it.peak / noise }) to Stats.median(bright.map { it.elongation })
        }
        val (snrOn, elOn) = quality(session(QualityMode.CONSERVATIVE))
        val (snrOff, elOff) = quality(session(QualityMode.OFF))
        // Rejecting the streaks keeps stars round; weighting keeps every useful bit of signal.
        assertTrue(elOn <= elOff, "elongation with filter $elOn vs without $elOff")
        assertTrue(snrOn >= 0.98 * snrOff, "star peak SNR with filter $snrOn vs without $snrOff")
    }
}
