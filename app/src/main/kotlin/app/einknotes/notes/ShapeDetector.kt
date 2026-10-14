package app.einknotes.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether a hand-drawn stroke was meant to be a clean shape, and if so which one.
 *
 * Several fitters (line, arrow, ellipse, polygon) each try to explain the stroke. Each reports how far the
 * stroke strays from its ideal shape, and the closest explanation wins if it is close enough. This mirrors
 * the approach found in Notability's shape detector (competing fitters, a distance based confidence, a
 * minimum confidence), written from scratch.
 *
 * Pure Kotlin so it can be tested off the device. All thresholds are in SCREEN pixels at the current zoom,
 * so a shape feels the same however far the page is zoomed.
 */
object ShapeDetector {

    class Detection(val kind: ShapeKind, val pts: FloatArray, val closed: Boolean, val confidence: Float, val label: String)

    private class P(val x: Float, val y: Float)

    private const val MIN_CONFIDENCE = 0.40f

    /** [xs],[ys] in page units; [scale] converts page units to screen pixels. Returns page-unit geometry. */
    fun detect(xs: FloatArray, ys: FloatArray, scale: Float): Detection? {
        if (xs.size < 8) return null
        val raw = ArrayList<P>(xs.size)
        for (i in xs.indices) raw.add(P(xs[i] * scale, ys[i] * scale))
        val pts = resample(raw, 5f)
        if (pts.size < 12) return null

        val length = pathLength(pts)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
        val diag = max(maxX - minX, maxY - minY)
        if (length < 90f || diag < 60f) return null

        val gap = dist(pts.first(), pts.last())
        val closed = gap < min(0.28f * length, 170f)
        // a closed stroke often runs a little past where it started; analyse only one trip round the loop
        val loop = if (closed) trimLoop(pts) else pts
        val loopLength = pathLength(loop)
        val corners = findCorners(loop, closed, loopLength)

        val candidates = ArrayList<Detection>()
        if (closed) {
            ellipse(loop, diag, corners.size)?.let { candidates.add(it) }
            polygon(loop, corners, diag)?.let { candidates.add(it) }
        } else {
            line(pts, length)?.let { candidates.add(it) }
            arrow(pts, length)?.let { candidates.add(it) }
        }
        val best = candidates.maxByOrNull { it.confidence } ?: return null
        if (best.confidence < MIN_CONFIDENCE) return null
        return Detection(best.kind, FloatArray(best.pts.size) { best.pts[it] / scale }, best.closed, best.confidence, best.label)
    }

    /** Test helper: describes what the detector sees (sizes, closure, corners). */
    fun debugInfo(xs: FloatArray, ys: FloatArray, scale: Float): String {
        val raw = ArrayList<P>(xs.size)
        for (i in xs.indices) raw.add(P(xs[i] * scale, ys[i] * scale))
        val pts = resample(raw, 5f)
        val length = pathLength(pts)
        val gap = dist(pts.first(), pts.last())
        val closed = gap < min(0.28f * length, 170f)
        val c = findCorners(pts, closed, length)
        return "n=${pts.size} L=${length.toInt()} gap=${gap.toInt()} closed=$closed corners=" +
            c.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
    }

    // ---- line ----------------------------------------------------------------------------------

    private fun line(pts: List<P>, length: Float): Detection? {
        val a = pts.first(); val b = pts.last()
        val chord = dist(a, b)
        if (chord < 60f || chord / length < 0.88f) return null
        var sum = 0f
        for (p in pts) sum += distToLine(p, a, b)
        val err = sum / pts.size
        val conf = (1f - err / (0.045f * chord)).coerceIn(0f, 1f)
        // snap to horizontal or vertical when it is nearly so
        var x0 = a.x; var y0 = a.y; var x1 = b.x; var y1 = b.y
        val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
        val snapH = abs(ang) < 6 || abs(abs(ang) - 180) < 6
        val snapV = abs(abs(ang) - 90) < 6
        if (snapH) { val my = (y0 + y1) / 2f; y0 = my; y1 = my }
        if (snapV) { val mx = (x0 + x1) / 2f; x0 = mx; x1 = mx }
        return Detection(ShapeKind.LINE, floatArrayOf(x0, y0, x1, y1), false, conf, "line")
    }

    // ---- arrow ---------------------------------------------------------------------------------

    /** A straight shaft with a short "V" head at the far end, drawn in one stroke. */
    private fun arrow(pts: List<P>, length: Float): Detection? {
        val start = pts.first()
        var far = 0f
        for (i in pts.indices) far = max(far, dist(pts[i], start))
        // the head strokes return to the tip; the tip is where the path FIRST gets (nearly) that far
        var tipIdx = 0
        for (i in pts.indices) if (dist(pts[i], start) >= far - 14f) { tipIdx = i; break }
        if (far < 80f || far < 0.55f * length) return null
        val tip = pts[tipIdx]
        // path before the tip must be a straight shaft
        var sum = 0f
        for (i in 0..tipIdx) sum += distToLine(pts[i], start, tip)
        val shaftErr = sum / (tipIdx + 1)
        // path after the tip is the head: long enough, and staying close to the tip
        var headLen = 0f
        for (i in tipIdx until pts.size - 1) headLen += dist(pts[i], pts[i + 1])
        if (headLen < 0.18f * length || headLen < 25f) return null
        var maxFromTip = 0f
        for (i in tipIdx until pts.size) maxFromTip = max(maxFromTip, dist(pts[i], tip))
        if (maxFromTip > 0.5f * far) return null
        val conf = (1f - shaftErr / (0.05f * far)).coerceIn(0f, 1f) * 0.9f
        return Detection(ShapeKind.ARROW, floatArrayOf(start.x, start.y, tip.x, tip.y), false, conf, "arrow")
    }

    // ---- ellipse / circle ----------------------------------------------------------------------

    private fun ellipse(pts: List<P>, diag: Float, cornerCount: Int): Detection? {
        val n = pts.size
        var cx = 0f; var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        cx /= n; cy /= n
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (p in pts) { val dx = p.x - cx; val dy = p.y - cy; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        var ux = cos(theta); var uy = sin(theta)
        val vx = -uy; val vy = ux
        var sa = 0f; var sb = 0f
        for (p in pts) { val dx = p.x - cx; val dy = p.y - cy; val a = dx * ux + dy * uy; val b = dx * vx + dy * vy; sa += a * a; sb += b * b }
        var ra = sqrt(2f * sa / n); var rb = sqrt(2f * sb / n)
        if (ra < 1f || rb < 1f) return null
        // refine: scale both radii so the mean normalised radius is exactly 1
        repeat(3) {
            var s = 0f
            for (p in pts) { val dx = p.x - cx; val dy = p.y - cy; val a = (dx * ux + dy * uy) / ra; val b = (dx * vx + dy * vy) / rb; s += sqrt(a * a + b * b) }
            val k = s / n
            ra *= k; rb *= k
        }
        // distance of every point from the ellipse (approximate): |1 - 1/rho| * distance from centre
        var sq = 0f
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            val a = (dx * ux + dy * uy) / ra; val b = (dx * vx + dy * vy) / rb
            val rho = max(sqrt(a * a + b * b), 0.01f)
            val d = abs(1f - 1f / rho) * hypot(dx, dy)
            sq += d * d
        }
        val err = sqrt(sq / n)
        var conf = (1f - err / (0.09f * diag)).coerceIn(0f, 1f)
        if (cornerCount >= 3) conf *= 0.45f       // a drawing with real corners is not an ellipse
        if (cornerCount == 2) conf *= 0.8f
        // snap: nearly round -> circle; nearly axis aligned -> aligned ellipse from the points' bounding box
        val roundish = abs(ra - rb) / max(ra, rb) < 0.14f
        val deg = Math.toDegrees(theta.toDouble())
        val m = ((deg % 90) + 90) % 90
        val aligned = m < 8 || m > 82
        if (roundish) {
            val r = (ra + rb) / 2f; ra = r; rb = r; ux = 1f; uy = 0f
        } else if (aligned) {
            var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (p in pts) { l = min(l, p.x); r = max(r, p.x); t = min(t, p.y); b = max(b, p.y) }
            cx = (l + r) / 2f; cy = (t + b) / 2f
            ra = (r - l) / 2f; rb = (b - t) / 2f
            ux = 1f; uy = 0f
        }
        val vx2 = -uy; val vy2 = ux
        return Detection(
            ShapeKind.ELLIPSE,
            floatArrayOf(cx, cy, cx + ra * ux, cy + ra * uy, cx + rb * vx2, cy + rb * vy2),
            true, conf, if (abs(ra - rb) < 0.5f) "circle" else "ellipse"
        )
    }

    // ---- polygon (triangle, rectangle, square, others) -----------------------------------------

    private fun polygon(pts: List<P>, corners: List<P>, diag: Float): Detection? {
        val n = corners.size
        if (n < 3 || n > 8) return null
        var verts: List<P> = corners
        var label = if (n == 3) "triangle" else if (n == 4) "quadrilateral" else "polygon"

        var judged: List<P> = corners
        if (n == 4 && looksRectangular(corners)) {
            val r = regularRectangle(pts, corners, snapAxis = false)
            judged = r.first                               // the tilted best fit, used to score the stroke
            val snapped = regularRectangle(pts, corners, snapAxis = true)
            verts = snapped.first; label = snapped.second
        } else judged = verts
        // distance of every stroke point from the polygon outline
        var sum = 0f
        for (p in pts) sum += distToPolygon(p, judged)
        val err = sum / pts.size
        var conf = (1f - err / (0.06f * diag)).coerceIn(0f, 1f)
        if (label == "rectangle" || label == "square") conf = min(1f, conf * 1.1f + 0.05f)
        val flat = FloatArray(verts.size * 2)
        for (i in verts.indices) { flat[i * 2] = verts[i].x; flat[i * 2 + 1] = verts[i].y }
        return Detection(ShapeKind.POLYGON, flat, true, conf, label)
    }

    /** All four corners close to 90 degrees. */
    private fun looksRectangular(c: List<P>): Boolean {
        for (i in 0 until 4) {
            val a = c[(i + 3) % 4]; val b = c[i]; val d = c[(i + 1) % 4]
            val ang = Math.toDegrees(angleBetween(a.x - b.x, a.y - b.y, d.x - b.x, d.y - b.y).toDouble())
            if (ang < 68 || ang > 112) return false
        }
        return true
    }

    /** Best rectangle aligned with the stroke's dominant direction; a square when the sides are nearly equal. */
    private fun regularRectangle(pts: List<P>, c: List<P>, snapAxis: Boolean): Pair<List<P>, String> {
        // dominant edge direction, averaged over the four edges modulo 90 degrees
        var sx = 0.0; var sy = 0.0
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()) * 4.0
            sx += cos(ang); sy += sin(ang)
        }
        var theta = (atan2(sy, sx) / 4.0).toFloat()
        // snap to axis when within 8 degrees
        val deg = Math.toDegrees(theta.toDouble())
        val m = ((deg % 90) + 90) % 90
        if (snapAxis && (m < 8 || m > 82)) theta = 0f
        val ct = cos(theta); val st = sin(theta)
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in pts) {
            val u = p.x * ct + p.y * st; val v = -p.x * st + p.y * ct
            l = min(l, u); r = max(r, u); t = min(t, v); b = max(b, v)
        }
        var w = r - l; var h = b - t
        var label = "rectangle"
        if (abs(w - h) / max(w, h) < 0.1f) {
            val s = (w + h) / 2f
            val cu = (l + r) / 2f; val cv = (t + b) / 2f
            l = cu - s / 2f; r = cu + s / 2f; t = cv - s / 2f; b = cv + s / 2f
            label = "square"
        }
        fun back(u: Float, v: Float) = P(u * ct - v * st, u * st + v * ct)
        return listOf(back(l, t), back(r, t), back(r, b), back(l, b)) to label
    }

    // ---- corners -------------------------------------------------------------------------------

    /** Points where the path turns sharply, found from the angle between the path before and after each point. */
    private fun findCorners(pts: List<P>, closed: Boolean, length: Float): List<P> {
        val n = pts.size
        val k = (min(max(0.05f * length, 20f), 45f) / 5f).toInt().coerceAtLeast(3)
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val i0 = i - k; val i1 = i + k
            if (!closed && (i0 < 0 || i1 >= n)) continue
            val a = pts[(i0 + n) % n]; val b = pts[i]; val c = pts[i1 % n]
            val ang = angleBetween(a.x - b.x, a.y - b.y, c.x - b.x, c.y - b.y)   // 180 deg = straight
            turn[i] = (PI.toFloat() - ang)                                       // 0 = straight, larger = sharper
        }
        val thresh = Math.toRadians(40.0).toFloat()
        // candidate corners: the sharpest sample of every run of high-turn samples
        val cand = ArrayList<IntArray>()      // [index]
        var i = 0
        while (i < n) {
            if (turn[i] > thresh) {
                var best = i; var j = i
                while (j < n && turn[j] > thresh * 0.8f) { if (turn[j] > turn[best]) best = j; j++ }
                cand.add(intArrayOf(best))
                i = j
            } else i++
        }
        // keep the sharpest ones, dropping any that lie within a corner's reach of a sharper one
        val minSep = max(36f, 0.07f * length)
        val kept = ArrayList<Int>()
        for (c in cand.sortedByDescending { turn[it[0]] }) {
            val idx = c[0]
            if (kept.none { dist(pts[it], pts[idx]) < minSep }) kept.add(idx)
        }
        kept.sort()
        return kept.map { pts[it] }
    }

    // ---- geometry helpers ----------------------------------------------------------------------

    private fun resample(p: List<P>, step: Float): List<P> {
        val out = ArrayList<P>()
        if (p.isEmpty()) return out
        out.add(p[0])
        var prev = p[0]; var acc = 0f; var i = 1
        while (i < p.size) {
            val d = dist(prev, p[i])
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = P(prev.x + (p[i].x - prev.x) * t, prev.y + (p[i].y - prev.y) * t)
                out.add(q); prev = q; acc = 0f
            } else { acc += d; prev = p[i]; i++ }
        }
        if (dist(out.last(), p.last()) > step * 0.4f) out.add(p.last())
        return out
    }

    /** Drops the overshoot: keeps the path up to the point where it comes back closest to its start. */
    private fun trimLoop(p: List<P>): List<P> {
        var bestJ = p.size - 1
        var bestD = dist(p.last(), p.first())
        for (j in (p.size * 0.6f).toInt() until p.size) {
            val d = dist(p[j], p.first())
            if (d < bestD) { bestD = d; bestJ = j }
        }
        return p.subList(0, bestJ + 1)
    }

    private fun pathLength(p: List<P>): Float { var s = 0f; for (i in 0 until p.size - 1) s += dist(p[i], p[i + 1]); return s }
    private fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    private fun distToLine(p: P, a: P, b: P): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len == 0f) return dist(p, a)
        return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }

    private fun distToSegment(p: P, a: P, b: P): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun distToPolygon(p: P, v: List<P>): Float {
        var best = Float.MAX_VALUE
        for (i in v.indices) best = min(best, distToSegment(p, v[i], v[(i + 1) % v.size]))
        return best
    }

    /** Angle in radians (0..pi) between two vectors. */
    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val la = hypot(ax, ay); val lb = hypot(bx, by)
        if (la == 0f || lb == 0f) return PI.toFloat()
        val c = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
        return acos(c)
    }
}
