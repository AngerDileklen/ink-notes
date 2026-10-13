package app.einknotes.notes

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** All coordinates in the model are "page units". One page unit is one pixel on this tablet at zoom 1. */
const val PAGE_W = 1404f
const val PAGE_H = 1872f

enum class Tool { PEN, ERASER, HAND }

enum class Paper(val label: String) {
    BLANK("blank"), RULED("ruled"), GRID("grid"), DOTS("dots");

    fun next() = values()[(ordinal + 1) % values().size]
}

/** Pressure (0..1, already normalised) -> pen width in page units. */
fun penWidthFor(pressure: Float): Float = 1.0f + 5.0f * pressure.coerceIn(0f, 1f).pow(0.8f)

/** Raw digitizer pressure on this tablet starts around 0.05 and tops out at 0.999. */
fun normalisePressure(raw: Float): Float = ((raw - 0.05f) / 0.94f).coerceIn(0f, 1f)

class Stroke(
    val tool: Tool,
    val xs: FloatArray,
    val ys: FloatArray,
    val ps: FloatArray   // normalised pressure per point
) {
    val n get() = xs.size
    val minX: Float
    val minY: Float
    val maxX: Float
    val maxY: Float

    init {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (i in xs.indices) {
            a = min(a, xs[i]); c = max(c, xs[i]); b = min(b, ys[i]); d = max(d, ys[i])
        }
        minX = a; minY = b; maxX = c; maxY = d
    }

    fun near(x: Float, y: Float, r: Float) = x >= minX - r && x <= maxX + r && y >= minY - r && y <= maxY + r

    /** True if the point (x,y) is within r of any part of the stroke. */
    fun hit(x: Float, y: Float, r: Float): Boolean {
        if (n == 1) return dist(x, y, xs[0], ys[0]) <= r
        for (i in 0 until n - 1) {
            if (distToSegment(x, y, xs[i], ys[i], xs[i + 1], ys[i + 1]) <= r) return true
        }
        return false
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return dist(px, py, ax + t * dx, ay + t * dy)
    }
}

class PageData(var paper: Paper = Paper.RULED) {
    val strokes = ArrayList<Stroke>()

    /** Page size in page units. Blank pages use the tablet's proportions; PDF pages use the PDF's own. */
    var w: Float = PAGE_W
    var h: Float = PAGE_H

    /** When set, the page background is page [pdfPage] (0-based) of this stored PDF file. */
    var pdfFile: String? = null
    var pdfPage: Int = 0

    val isPdf get() = pdfFile != null
}

class NoteData {
    val pages = ArrayList<PageData>()

    init { pages.add(PageData()) }
}

/** Small growable float buffer so strokes can be collected without boxing. */
class FloatList(capacity: Int = 128) {
    private var a = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
    fun clear() { size = 0 }
}

/** Undo/redo entries. Every entry knows which page it belongs to. */
sealed class Cmd(val page: PageData) {
    class Add(page: PageData, val stroke: Stroke) : Cmd(page)

    /** [removed] is in the order the strokes were taken out; each index was valid at that moment. */
    class Remove(page: PageData, val removed: List<Pair<Int, Stroke>>) : Cmd(page)
}
