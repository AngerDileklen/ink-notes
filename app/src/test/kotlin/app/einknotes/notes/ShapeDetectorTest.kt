package app.einknotes.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Scores the shape detector on synthetic "hand drawn" strokes. Prints a table; fails if accuracy is poor. */
class ShapeDetectorTest {

    private class Stroke2(val xs: FloatArray, val ys: FloatArray)

    private val rnd = Random(42)

    /** Turns an ideal polyline into something that looks hand drawn: pen-rate sampling, wobble and jitter. */
    private fun handDrawn(ideal: List<Pair<Double, Double>>, wobble: Double = 4.0, jitter: Double = 1.0): Stroke2 {
        // sample every ~3 px along the ideal path
        val pts = ArrayList<Pair<Double, Double>>()
        for (i in 0 until ideal.size - 1) {
            val (x0, y0) = ideal[i]; val (x1, y1) = ideal[i + 1]
            val d = hypot(x1 - x0, y1 - y0)
            val n = maxOf(1, (d / 3.0).toInt())
            for (k in 0 until n) pts.add(Pair(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n))
        }
        pts.add(ideal.last())
        val ph1 = rnd.nextDouble() * 6.28; val ph2 = rnd.nextDouble() * 6.28
        val xs = FloatArray(pts.size); val ys = FloatArray(pts.size)
        for (i in pts.indices) {
            val t = i / 40.0
            val wx = wobble * sin(t * 0.9 + ph1) + wobble * 0.5 * sin(t * 2.3 + ph2)
            val wy = wobble * cos(t * 0.8 + ph2) + wobble * 0.5 * sin(t * 1.9 + ph1)
            xs[i] = (pts[i].first + wx + rnd.nextGaussian() * jitter).toFloat()
            ys[i] = (pts[i].second + wy + rnd.nextGaussian() * jitter).toFloat()
        }
        return Stroke2(xs, ys)
    }

    private fun rotate(p: List<Pair<Double, Double>>, deg: Double, cx: Double, cy: Double) = p.map {
        val a = Math.toRadians(deg); val dx = it.first - cx; val dy = it.second - cy
        Pair(cx + dx * cos(a) - dy * sin(a), cy + dx * sin(a) + dy * cos(a))
    }

    private fun circle(r: Double, ry: Double = r, rot: Double = 0.0, overshoot: Double = 0.04, gapDeg: Double = 0.0): List<Pair<Double, Double>> {
        val start = rnd.nextDouble() * 2 * PI
        val total = 2 * PI * (1.0 + overshoot) - Math.toRadians(gapDeg)
        val n = 90
        val p = (0..n).map { val a = start + total * it / n; Pair(500 + r * cos(a), 500 + ry * sin(a)) }
        return rotate(p, rot, 500.0, 500.0)
    }

    private fun polygon(v: List<Pair<Double, Double>>, rot: Double, overshoot: Double = 0.03): List<Pair<Double, Double>> {
        // start somewhere on the first edge, go round, overshoot slightly
        val closedPts = v + listOf(v[0], Pair(v[0].first + (v[1].first - v[0].first) * overshoot * 6, v[0].second + (v[1].second - v[0].second) * overshoot * 6))
        return rotate(closedPts, rot, 500.0, 500.0)
    }

    private fun rect(w: Double, h: Double, rot: Double) = polygon(listOf(
        Pair(500 - w / 2, 500 - h / 2), Pair(500 + w / 2, 500 - h / 2), Pair(500 + w / 2, 500 + h / 2), Pair(500 - w / 2, 500 + h / 2)), rot)

    private fun triangle(s: Double, rot: Double) = polygon(listOf(
        Pair(500.0, 500 - s * 0.6), Pair(500 + s * 0.55, 500 + s * 0.4), Pair(500 - s * 0.55, 500 + s * 0.4)), rot)

    private fun line(len: Double, deg: Double, bow: Double = 6.0): List<Pair<Double, Double>> {
        val a = Math.toRadians(deg)
        return (0..40).map { val t = it / 40.0; val off = bow * sin(t * PI)
            Pair(500 + (t - 0.5) * len * cos(a) - off * sin(a), 500 + (t - 0.5) * len * sin(a) + off * cos(a)) }
    }

    private fun arrow(len: Double, deg: Double): List<Pair<Double, Double>> {
        val a = Math.toRadians(deg)
        fun at(t: Double, off: Double) = Pair(500 + (t - 0.5) * len * cos(a) - off * sin(a), 500 + (t - 0.5) * len * sin(a) + off * cos(a))
        val head = 45.0
        val tip = at(1.0, 0.0)
        val h1 = Pair(tip.first - head * cos(a - 0.5), tip.second - head * sin(a - 0.5))
        val h2 = Pair(tip.first - head * cos(a + 0.5), tip.second - head * sin(a + 0.5))
        return listOf(at(0.0, 0.0), at(0.5, 3.0), tip, h1, tip, h2)
    }

    private fun scribble(): List<Pair<Double, Double>> {
        var x = 500.0; var y = 500.0
        return (0..80).map { x += rnd.nextGaussian() * 45; y += rnd.nextGaussian() * 45; Pair(x, y) }
    }

    private fun wave(): List<Pair<Double, Double>> = (0..80).map { Pair(300.0 + it * 6, 500 + 80 * sin(it / 8.0)) }
    private fun spiral(): List<Pair<Double, Double>> = (0..120).map { val a = it / 12.0; Pair(500 + (20 + it * 1.8) * cos(a), 500 + (20 + it * 1.8) * sin(a)) }
    private fun sCurve(): List<Pair<Double, Double>> = (0..60).map { val t = it / 60.0; Pair(300 + 400 * t, 500 + 120 * sin(t * 2 * PI)) }

    private class Case(val name: String, val expect: String?, val make: () -> List<Pair<Double, Double>>)

    @Test
    fun scoresTheDetector() {
        val cases = ArrayList<Case>()
        repeat(14) { i -> cases.add(Case("circle", "circle") { circle(110.0 + i * 12, overshoot = 0.02 + (i % 4) * 0.02, gapDeg = if (i % 5 == 0) 10.0 else 0.0) }) }
        repeat(10) { i -> cases.add(Case("ellipse", "ellipse") { circle(210.0 + i * 8, 120.0 + i * 3, rot = if (i % 2 == 0) 0.0 else 25.0 * (i % 3)) }) }
        repeat(14) { i -> cases.add(Case("rectangle", "rectangle") { rect(300.0 + i * 18, 170.0 + i * 6, (i % 5 - 2) * 3.0) }) }
        repeat(8) { i -> cases.add(Case("tilted rect", "rectangle") { rect(330.0 + i * 15, 190.0 + i * 5, 12.0 + i * 4.0) }) }
        repeat(8) { i -> cases.add(Case("square", "square") { rect(240.0 + i * 20, 240.0 + i * 20, (i % 3 - 1) * 3.0) }) }
        repeat(10) { i -> cases.add(Case("triangle", "triangle") { triangle(260.0 + i * 15, (i % 5 - 2) * 8.0) }) }
        repeat(6) { i -> cases.add(Case("rotated tri", "triangle") { triangle(280.0 + i * 20, 30.0 + i * 25.0) }) }
        repeat(14) { i -> cases.add(Case("line", "line") { line(240.0 + i * 25, i * 24.0 - 80.0) }) }
        repeat(8) { i -> cases.add(Case("arrow", "arrow") { arrow(300.0 + i * 30, i * 40.0 - 100.0) }) }
        repeat(8) { cases.add(Case("scribble", null) { scribble() }) }
        repeat(4) { cases.add(Case("wave", null) { wave() }) }
        repeat(3) { cases.add(Case("spiral", null) { spiral() }) }
        repeat(3) { cases.add(Case("S-curve", null) { sCurve() }) }

        val byName = LinkedHashMap<String, IntArray>()   // name -> [correct, total]
        val misses = ArrayList<String>()
        val debugShown = HashMap<String, Int>()
        for (scale in listOf(1f, 0.86f, 1.5f)) {
            for (c in cases) {
                val s = handDrawn(c.make(), wobble = 4.0, jitter = 1.0)
                val d = ShapeDetector.detect(s.xs.map { it / scale }.toFloatArray(), s.ys.map { it / scale }.toFloatArray(), scale)
                val got = d?.label
                val ok = if (c.expect == null) d == null else got == c.expect
                val e = byName.getOrPut(c.name) { IntArray(2) }
                e[1]++; if (ok) e[0]++ else {
                    misses.add("${c.name}@$scale -> ${got ?: "none"} (${"%.2f".format(d?.confidence ?: 0f)})")
                    if (debugShown.getOrPut(c.name) { 0 } < 2) {
                        debugShown[c.name] = debugShown[c.name]!! + 1
                        println("DEBUG ${c.name}@$scale got=$got: " + ShapeDetector.debugInfo(s.xs.map { it / scale }.toFloatArray(), s.ys.map { it / scale }.toFloatArray(), scale))
                    }
                }
            }
        }
        println("---- shape detector scores ----")
        var good = 0; var total = 0
        for ((k, v) in byName) { println("%-10s %2d / %2d".format(k, v[0], v[1])); good += v[0]; total += v[1] }
        println("overall    $good / $total")
        misses.groupingBy { it.substringBefore("@") + " -> " + it.substringAfter("-> ").substringBefore(" (") }.eachCount().forEach { (k, n) -> println("  miss: $k  x$n") }
        assertTrue("accuracy too low: $good / $total", good >= total * 0.85)
    }

    @Test
    fun replaysDeviceStrokes() {
        val f = java.io.File("/tmp/shapes.sh")
        if (!f.exists()) return
        var xs = ArrayList<Float>(); var ys = ArrayList<Float>()
        val scale = 0.86f
        for (line in f.readLines()) {
            val t = line.trim().split(" ")
            if (t.size >= 5 && (t[3] == "DOWN" || t[3] == "MOVE" || t[3] == "UP")) { xs.add(t[4].toFloat() / scale); ys.add(t[5].toFloat() / scale) }
            if (t.size >= 5 && t[3] == "UP") {
                val d = ShapeDetector.detect(xs.toFloatArray(), ys.toFloatArray(), scale)
                println("REPLAY n=${xs.size} -> ${d?.label} ${d?.confidence}  " + ShapeDetector.debugInfo(xs.toFloatArray(), ys.toFloatArray(), scale))
                xs = ArrayList(); ys = ArrayList()
            }
        }
    }
}
