package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

data class Registration(
    /** Maps frame pixel coordinates → reference (stack) coordinates. */
    val transform: Similarity,
    val inliers: Int,
    val rmsPx: Double,
    /** "fast" (prediction), "blind" (triangles) or "sparse" (one to four stars). */
    val method: String,
)

/**
 * Registers a frame's stars against the reference stars.
 * 1. Fast path: apply the predicted transform and match nearest neighbours (normal case).
 * 2. Blind path: triangle invariants + RANSAC (first frame, after a GoTo, after a bump).
 * 3. Refinement: iterative weighted least squares with outlier rejection.
 */
class Registrar(
    private val reference: List<Star>,
    private val minInliers: Int = 6,
    private val maxRmsPx: Double = 0.7,
    private val seed: Int = 1234,
) {
    private val refX = DoubleArray(reference.size) { reference[it].x }
    private val refY = DoubleArray(reference.size) { reference[it].y }
    private val refTriangles: List<Triangle> by lazy { triangles(reference) }

    /**
     * [fwhm] of the frame's stars: the allowed residual scales with the star size (an
     * alignment error of a quarter of the FWHM is invisible; softer stars have noisier centroids).
     */
    fun register(stars: List<Star>, predicted: Similarity?, fwhm: Double = 3.0): Registration? {
        val dense = registerDense(stars, predicted, fwhm)
        if (dense != null || minOf(stars.size, reference.size) >= 5) return dense
        return sparse(stars, predicted)
    }

    private fun registerDense(stars: List<Star>, predicted: Similarity?, fwhm: Double): Registration? {
        rmsLimit = max(maxRmsPx, 0.25 * (if (fwhm.isNaN()) 3.0 else fwhm))
        if (stars.size < 3 || reference.size < 3) return null
        predicted?.let { pred ->
            refine(stars, pred, matchRadius = 4.0)?.let { r ->
                if (accept(r, stars)) return r.copy(method = "fast")
                // Only a genuinely sparse field (few stars detected) may be registered with 3–5
                // matches, and only if they agree with the prediction. With many stars, a few
                // matches are chance coincidences and would create double stars.
                val sparse = stars.size < 10 || reference.size < 10
                if (sparse && r.inliers >= 3 && r.rmsPx <= rmsLimit && agrees(r.transform, pred)) return r.copy(method = "fast")
            }
        }
        val blind = blind(stars) ?: return null
        val refined = refine(stars, blind, matchRadius = 2.5) ?: return null
        return if (accept(refined, stars)) refined.copy(method = "blind") else null
    }

    private var rmsLimit = maxRmsPx

    /**
     * Sparse fields (a phone at the eyepiece often sees one or two stars): the brightest stars (up
     * to 4) are matched near where the prediction puts them. One match gives the shift (rotation and
     * scale from the prediction), two or more give the rotation too. Without a match near the
     * prediction, the brightest frame star goes to the brightest reference star if that is a
     * plausible jump. A result far from the prediction is refused rather than misplaced.
     */
    private fun sparse(stars: List<Star>, predicted: Similarity?): Registration? {
        if (stars.isEmpty() || reference.isEmpty()) return null
        val base = predicted ?: Similarity.IDENTITY
        val fIdx = stars.indices.sortedByDescending { stars[it].flux }.take(4)
        val rIdx = reference.indices.sortedByDescending { reference[it].flux }.take(4)
        fun gap(i: Int, j: Int) = hypot(refX[j] - base.applyX(stars[i].x, stars[i].y), refY[j] - base.applyY(stars[i].x, stars[i].y))
        var pairs = ArrayList<Pair<Int, Int>>()
        for (i in fIdx) {
            val j = rIdx.filter { j -> pairs.none { it.second == j } }.minByOrNull { gap(i, it) } ?: continue
            if (gap(i, j) <= SPARSE_RADIUS_PX) pairs.add(i to j)
        }
        if (pairs.isEmpty() && gap(fIdx[0], rIdx[0]) <= SPARSE_MAX_JUMP_PX) pairs.add(fIdx[0] to rIdx[0])
        if (pairs.isEmpty()) return null
        val (i0, j0) = pairs[0]
        var t = base.copy(
            tx = base.tx + refX[j0] - base.applyX(stars[i0].x, stars[i0].y),
            ty = base.ty + refY[j0] - base.applyY(stars[i0].x, stars[i0].y),
        )
        val fit = if (pairs.size >= 2) Similarity.fit(
            DoubleArray(pairs.size) { stars[pairs[it].first].x }, DoubleArray(pairs.size) { stars[pairs[it].first].y },
            DoubleArray(pairs.size) { refX[pairs[it].second] }, DoubleArray(pairs.size) { refY[pairs[it].second] },
            DoubleArray(pairs.size) { minOf(stars[pairs[it].first].snr, reference[pairs[it].second].snr).coerceIn(1.0, 200.0) },
        )?.withScaleClamped(0.97, 1.03) else null
        if (fit != null && abs(fit.angleDeg - base.angleDeg) <= 3.0) t = fit else pairs = arrayListOf(pairs[0])
        val res = pairs.map { (i, j) -> Similarity.distance(t, stars[i].x, stars[i].y, refX[j], refY[j]) }
        val rms = sqrt(res.sumOf { it * it } / res.size)
        if (rms > max(rmsLimit, 1.5)) return null
        if (predicted != null && hypot(t.tx - predicted.tx, t.ty - predicted.ty) > SPARSE_MAX_JUMP_PX) return null
        return Registration(t, pairs.size, rms, "sparse")
    }

    private fun accept(r: Registration, stars: List<Star>): Boolean {
        val minCount = minOf(stars.size, reference.size)
        return r.inliers >= minInliers && r.rmsPx <= rmsLimit && r.inliers >= 0.3 * minOf(minCount, 40)
    }

    private fun agrees(t: Similarity, p: Similarity): Boolean {
        val dx = t.tx - p.tx
        val dy = t.ty - p.ty
        return hypot(dx, dy) < 2.0 && abs(t.angleDeg - p.angleDeg) < 0.2
    }

    /** Iterative matching + weighted fit starting from [start]. */
    private fun refine(stars: List<Star>, start: Similarity, matchRadius: Double): Registration? {
        var t = start
        var radius = matchRadius
        var last: Registration? = null
        repeat(5) {
            val pairs = match(stars, t, radius)
            if (pairs.size < 3) return last
            val px = DoubleArray(pairs.size) { stars[pairs[it].first].x }
            val py = DoubleArray(pairs.size) { stars[pairs[it].first].y }
            val qx = DoubleArray(pairs.size) { refX[pairs[it].second] }
            val qy = DoubleArray(pairs.size) { refY[pairs[it].second] }
            val w = DoubleArray(pairs.size) { minOf(stars[pairs[it].first].snr, reference[pairs[it].second].snr).coerceIn(1.0, 200.0) }
            var fit = Similarity.fit(px, py, qx, qy, w)?.withScaleClamped(0.97, 1.03) ?: return last
            // Reject outliers (> 3 robust σ) and refit once.
            val res = DoubleArray(pairs.size) { Similarity.distance(fit, px[it], py[it], qx[it], qy[it]) }
            val sig = max(0.05, 1.4826 * Stats.median(res))
            val keep = res.indices.filter { res[it] <= max(3 * sig, 0.3) }
            if (keep.size >= 3 && keep.size < pairs.size) {
                fit = Similarity.fit(
                    DoubleArray(keep.size) { px[keep[it]] }, DoubleArray(keep.size) { py[keep[it]] },
                    DoubleArray(keep.size) { qx[keep[it]] }, DoubleArray(keep.size) { qy[keep[it]] },
                    DoubleArray(keep.size) { w[keep[it]] },
                )?.withScaleClamped(0.97, 1.03) ?: fit
            }
            val finalRes = keep.map { Similarity.distance(fit, px[it], py[it], qx[it], qy[it]) }
            val rms = sqrt(finalRes.sumOf { it * it } / max(1, finalRes.size))
            last = Registration(fit, keep.size, rms, "")
            t = fit
            radius = max(1.0, minOf(radius, 4 * rms + 0.5))
        }
        return last
    }

    /** Mutual nearest neighbours within [radius] after applying [t]. */
    private fun match(stars: List<Star>, t: Similarity, radius: Double): List<Pair<Int, Int>> {
        val tx = DoubleArray(stars.size) { t.applyX(stars[it].x, stars[it].y) }
        val ty = DoubleArray(stars.size) { t.applyY(stars[it].x, stars[it].y) }
        val bestForFrame = IntArray(stars.size) { -1 }
        for (i in stars.indices) {
            var best = -1
            var bd = radius
            for (j in reference.indices) {
                val d = hypot(tx[i] - refX[j], ty[i] - refY[j])
                if (d < bd) { bd = d; best = j }
            }
            bestForFrame[i] = best
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (i in stars.indices) {
            val j = bestForFrame[i]
            if (j < 0) continue
            // Mutual check: no other frame star closer to this reference star.
            var mutual = true
            val dij = hypot(tx[i] - refX[j], ty[i] - refY[j])
            for (k in stars.indices) if (k != i && bestForFrame[k] == j && hypot(tx[k] - refX[j], ty[k] - refY[j]) < dij) { mutual = false; break }
            if (mutual) out.add(i to j)
        }
        return out
    }

    // --- Blind matching -------------------------------------------------------------

    private class Triangle(val i: Int, val j: Int, val k: Int, val inv1: Double, val inv2: Double, val longest: Double, val orient: Int)

    /** Triangles from each star and pairs of its 6 nearest neighbours (brightest 40 stars). */
    private fun triangles(stars: List<Star>): List<Triangle> {
        val s = stars.take(40)
        val seen = HashSet<Long>()
        val out = ArrayList<Triangle>()
        for (a in s.indices) {
            val nn = s.indices.filter { it != a }.sortedBy { hypot(s[it].x - s[a].x, s[it].y - s[a].y) }.take(6)
            for (p in nn.indices) for (q in p + 1 until nn.size) {
                val ids = intArrayOf(a, nn[p], nn[q]).sorted()
                val key = ids[0].toLong() * 1_000_000 + ids[1] * 1000L + ids[2]
                if (!seen.add(key)) continue
                makeTriangle(s, ids[0], ids[1], ids[2])?.let { out.add(it) }
            }
        }
        return out.sortedBy { it.inv1 }
    }

    /**
     * Canonical labelling: vertex A is opposite the longest side, C opposite the shortest.
     * Invariants (b/a, c/a) don't change with rotation, translation or scale.
     */
    private fun makeTriangle(s: List<Star>, i0: Int, i1: Int, i2: Int): Triangle? {
        val v = intArrayOf(i0, i1, i2)
        fun len(p: Int, q: Int) = hypot(s[p].x - s[q].x, s[p].y - s[q].y)
        // Side opposite vertex v[n] connects the other two.
        val opp = doubleArrayOf(len(v[1], v[2]), len(v[0], v[2]), len(v[0], v[1]))
        val order = (0..2).sortedByDescending { opp[it] } // A (longest opposite), B, C
        val a = opp[order[0]]; val b = opp[order[1]]; val c = opp[order[2]]
        if (a < 10 || c / a < 0.1) return null
        if ((a - b) / a < 0.01 || (b - c) / a < 0.01) return null // ambiguous labelling
        val pa = s[v[order[0]]]; val pb = s[v[order[1]]]; val pc = s[v[order[2]]]
        val cross = (pb.x - pa.x) * (pc.y - pa.y) - (pb.y - pa.y) * (pc.x - pa.x)
        return Triangle(v[order[0]], v[order[1]], v[order[2]], b / a, c / a, a, if (cross > 0) 1 else -1)
    }

    private fun blind(stars: List<Star>): Similarity? {
        val frameTris = triangles(stars)
        val refTris = refTriangles
        if (frameTris.isEmpty() || refTris.isEmpty()) return null
        val eps = 0.006
        val votes = HashMap<Long, Int>()
        val inv1 = DoubleArray(refTris.size) { refTris[it].inv1 }
        val fs = stars.take(40)
        val rs = reference.take(40)
        for (ft in frameTris) {
            var lo = lowerBound(inv1, ft.inv1 - eps)
            while (lo < refTris.size && refTris[lo].inv1 <= ft.inv1 + eps) {
                val rt = refTris[lo]
                lo++
                if (abs(rt.inv2 - ft.inv2) > eps || rt.orient != ft.orient) continue
                val ratio = rt.longest / ft.longest
                if (ratio < 0.95 || ratio > 1.05) continue
                for ((f, r) in listOf(ft.i to rt.i, ft.j to rt.j, ft.k to rt.k)) {
                    val key = f.toLong() * 100_000 + r
                    votes[key] = (votes[key] ?: 0) + 1
                }
            }
        }
        if (votes.isEmpty()) return null
        val corr = votes.entries.sortedByDescending { it.value }.take(200).map { (it.key / 100_000).toInt() to (it.key % 100_000).toInt() }
        if (corr.size < 2) return null
        val rnd = Random(seed)
        var best: Similarity? = null
        var bestScore = 0
        val allX = DoubleArray(stars.size) { stars[it].x }
        val allY = DoubleArray(stars.size) { stars[it].y }
        val iterations = minOf(2000, corr.size * corr.size)
        repeat(iterations) {
            val p = corr[rnd.nextInt(corr.size)]
            val q = corr[rnd.nextInt(corr.size)]
            if (p.first == q.first || p.second == q.second) return@repeat
            val t = Similarity.fit(
                doubleArrayOf(fs[p.first].x, fs[q.first].x), doubleArrayOf(fs[p.first].y, fs[q.first].y),
                doubleArrayOf(rs[p.second].x, rs[q.second].x), doubleArrayOf(rs[p.second].y, rs[q.second].y),
            ) ?: return@repeat
            if (t.scale < 0.95 || t.scale > 1.05) return@repeat
            var score = 0
            for (i in allX.indices) {
                val x = t.applyX(allX[i], allY[i])
                val y = t.applyY(allX[i], allY[i])
                for (j in refX.indices) if (hypot(x - refX[j], y - refY[j]) < 1.5) { score++; break }
            }
            if (score > bestScore) { bestScore = score; best = t }
        }
        return if (bestScore >= 3) best else null
    }

    private fun lowerBound(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (a[m] < v) lo = m + 1 else hi = m
        }
        return lo
    }

    companion object {
        /** Sparse fields: how far from its predicted place a star may be matched. */
        const val SPARSE_RADIUS_PX = 12.0
        /** Sparse fields: the largest shift from the prediction that is still believed. */
        const val SPARSE_MAX_JUMP_PX = 80.0
    }
}
