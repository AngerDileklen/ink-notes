package app.einknotes.notes

import android.graphics.Matrix
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** All coordinates in the model are "page units". One page unit is one pixel on this tablet at zoom 1. */
const val PAGE_W = 1404f
/** A4 proportions (1 : 1.4142) at the tablet's width. Older notebooks keep the page sizes they were made with. */
const val PAGE_H = 1985.6f

/** What the pen does when it touches the page. */
enum class Tool { PEN, HIGHLIGHTER, CALLIGRAPHY, ERASER, SELECT, SHAPE, HAND, TEXT, TAPE }

/** How a stroke looks. */
enum class Brush { PEN, HIGHLIGHTER, CALLIGRAPHY }

enum class Paper(val label: String) {
    BLANK("blank"), RULED("ruled"), GRID("squares (grid)"), DOTS("dots"), NARROW("narrow ruled"), CORNELL("Cornell notes"), MUSIC("music staff"),
    // added later: stored by position, so new ones always go at the end
    ENGINEERING("engineering grid"), HEX("hexagonal grid"), ISOMETRIC("isometric dots"), TWO_COL("two columns"), THREE_COL("three columns"),
    DAILY("daily planner"), WEEKLY("weekly planner"), MONTHLY("monthly planner"), COLLEGE("college ruled"), MANUSCRIPT("handwriting practice");

    fun next() = values()[(ordinal + 1) % values().size]
}

/** Page proportions. The page is always as wide as the tablet; the height follows the paper. */
enum class PageSize(val label: String, val ratio: Float) {
    A4("A4", 1.41421356f), LETTER("US Letter", 11f / 8.5f), LEGAL("US Legal", 14f / 8.5f), TABLET("Tablet screen (3:4)", 4f / 3f);

    companion object {
        fun of(w: Float, h: Float): PageSize? = values().firstOrNull { Math.abs(h / w - it.ratio) < 0.01f }
    }
}

/** Pressure (0..1, already normalised) -> pen width in page units. */
fun penWidthFor(pressure: Float): Float = 1.0f + 5.0f * pressure.coerceIn(0f, 1f).pow(0.8f)

/** Raw digitizer pressure on this tablet starts around 0.05 and tops out at 0.999. */
fun normalisePressure(raw: Float): Float = ((raw - 0.05f) / 0.94f).coerceIn(0f, 1f)

/** Width of a highlighter stroke in page units at widthScale 1. */
const val HIGHLIGHTER_WIDTH = 30f

// ---------------------------------------------------------------------------------------------
// Items: everything that can sit on a page. Strokes now; shapes, text and images come next.
// ---------------------------------------------------------------------------------------------

abstract class Item {
    var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f

    /** The audio recording that was running when this was written (0 = none) and how many ms into it. */
    var recId = 0
    var recMs = 0

    abstract fun recomputeBounds()
    /** True if (x,y) is within r of the item's ink. Used by the eraser and by tap-to-select. */
    abstract fun hit(x: Float, y: Float, r: Float): Boolean
    /** Moves/scales the item in place. */
    abstract fun transform(m: Matrix)
    abstract fun copy(): Item
    /** Fraction (0..1) of the item that lies inside the closed polygon [poly] (x,y pairs). Used by the lasso. */
    abstract fun fractionInside(poly: FloatArray): Float

    fun near(x: Float, y: Float, r: Float) = x >= minX - r && x <= maxX + r && y >= minY - r && y <= maxY + r
    fun intersects(l: Float, t: Float, r: Float, b: Float) = !(maxX < l || minX > r || maxY < t || minY > b)
}

class Stroke(
    val brush: Brush,
    var gray: Int,              // 0 = black .. 255 = white
    var widthScale: Float,      // multiplier on the brush's base width
    var xs: FloatArray,
    var ys: FloatArray,
    var ps: FloatArray,         // normalised pressure per point
    var nibAngle: Float = (Math.PI / 2).toFloat(),   // calligraphy only
    var nibFlat: Float = 0.75f                       // calligraphy only
) : Item() {
    val n get() = xs.size

    init { recomputeBounds() }

    override fun recomputeBounds() {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (i in xs.indices) { a = min(a, xs[i]); c = max(c, xs[i]); b = min(b, ys[i]); d = max(d, ys[i]) }
        // ink has thickness: pad the bounds by the widest part of the stroke
        val pad = if (brush == Brush.HIGHLIGHTER) HIGHLIGHTER_WIDTH * widthScale / 2f else 6f * widthScale / 2f + 1f
        minX = a - pad; minY = b - pad; maxX = c + pad; maxY = d + pad
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean {
        val rr = r + if (brush == Brush.HIGHLIGHTER) HIGHLIGHTER_WIDTH * widthScale / 2f else 0f
        if (n == 1) return dist(x, y, xs[0], ys[0]) <= rr
        for (i in 0 until n - 1) {
            if (distToSegment(x, y, xs[i], ys[i], xs[i + 1], ys[i + 1]) <= rr) return true
        }
        return false
    }

    override fun transform(m: Matrix) {
        val pts = FloatArray(n * 2)
        for (i in 0 until n) { pts[i * 2] = xs[i]; pts[i * 2 + 1] = ys[i] }
        m.mapPoints(pts)
        for (i in 0 until n) { xs[i] = pts[i * 2]; ys[i] = pts[i * 2 + 1] }
        widthScale *= m.mapRadius(1f)
        recomputeBounds()
    }

    override fun copy(): Item = Stroke(brush, gray, widthScale, xs.copyOf(), ys.copyOf(), ps.copyOf(), nibAngle, nibFlat)

    override fun fractionInside(poly: FloatArray): Float {
        if (n == 0) return 0f
        var inside = 0
        for (i in 0 until n) if (pointInPolygon(xs[i], ys[i], poly)) inside++
        return inside / n.toFloat()
    }

    /**
     * Removes the part of the stroke that lies inside the circle (cx, cy, r) and returns what is left as new
     * strokes (the stroke may be split in two or more). Returns null if the circle does not touch the stroke.
     */
    fun eraseCircle(cx: Float, cy: Float, r: Float): List<Stroke>? {
        if (n == 0 || !near(cx, cy, r)) return null
        val runs = ArrayList<FloatArray>()      // each run: x,y,p triples
        var cur = ArrayList<Float>()
        var touched = false
        fun flush() { if (cur.size >= 6) runs.add(cur.toFloatArray()); cur = ArrayList() }
        fun inside(i: Int): Boolean { val dx = xs[i] - cx; val dy = ys[i] - cy; return dx * dx + dy * dy <= r * r }
        for (i in 0 until n) {
            val insideNow = inside(i)
            if (i > 0) {
                // does the segment from the previous point cross the circle even though both ends are outside?
                val ax = xs[i - 1]; val ay = ys[i - 1]; val bx = xs[i]; val by = ys[i]
                val dx = bx - ax; val dy = by - ay
                val fx = ax - cx; val fy = ay - cy
                val a = dx * dx + dy * dy; val b = 2 * (fx * dx + fy * dy); val c = fx * fx + fy * fy - r * r
                val disc = b * b - 4 * a * c
                if (a > 0f && disc > 0f) {
                    val sq = sqrt(disc)
                    val t0 = (-b - sq) / (2 * a); val t1 = (-b + sq) / (2 * a)
                    val enter = t0 in 0f..1f; val leave = t1 in 0f..1f
                    if (enter || leave) {
                        touched = true
                        val p0 = ps[i - 1]; val p1 = ps[i]
                        if (enter && !inside(i - 1)) {                 // path runs into the circle: end the run at the edge
                            cur.add(ax + dx * t0); cur.add(ay + dy * t0); cur.add(p0 + (p1 - p0) * t0)
                            flush()
                        }
                        if (leave && !insideNow) {                     // path comes out the other side: start a new run there
                            cur = ArrayList()
                            cur.add(ax + dx * t1); cur.add(ay + dy * t1); cur.add(p0 + (p1 - p0) * t1)
                        }
                    }
                }
            }
            if (insideNow) { touched = true; flush() }
            else { cur.add(xs[i]); cur.add(ys[i]); cur.add(ps[i]) }
        }
        flush()
        if (!touched) return null
        return runs.map { run ->
            val k = run.size / 3
            Stroke(brush, gray, widthScale, FloatArray(k) { run[it * 3] }, FloatArray(k) { run[it * 3 + 1] }, FloatArray(k) { run[it * 3 + 2] }, nibAngle, nibFlat)
                .also { it.recId = recId; it.recMs = recMs }
        }
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

/** Even-odd rule point-in-polygon test. [poly] is x0,y0,x1,y1,... */
fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
    var inside = false
    val n = poly.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = poly[i * 2]; val yi = poly[i * 2 + 1]; val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}


// ---------------------------------------------------------------------------------------------
// Shapes: lines, arrows, ellipses and polygons (rectangles and triangles are polygons).
// ---------------------------------------------------------------------------------------------

enum class ShapeKind { LINE, ARROW, ELLIPSE, POLYGON }

/** Base line width of a shape in page units at widthScale 1. */
const val SHAPE_WIDTH = 3.2f

/**
 * [pts] holds x,y pairs in page units.
 *  LINE / ARROW: tail, head.   ELLIPSE: centre, end of one semi-axis, end of the other (so any affine
 *  transform, including rotation and stretching, keeps it an ellipse).   POLYGON: the vertices.
 */
class ShapeItem(
    val kind: ShapeKind,
    var pts: FloatArray,
    val closed: Boolean,
    var gray: Int = 0,
    var widthScale: Float = 1f
) : Item() {

    init { recomputeBounds() }

    /** The shape's outline as a polyline (x,y pairs); closed shapes repeat their first point at the end. */
    fun outline(): FloatArray = when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW -> pts.copyOf(4)
        ShapeKind.POLYGON -> if (closed) pts + floatArrayOf(pts[0], pts[1]) else pts.copyOf()
        ShapeKind.ELLIPSE -> {
            val cx = pts[0]; val cy = pts[1]
            val ax = pts[2] - cx; val ay = pts[3] - cy; val bx = pts[4] - cx; val by = pts[5] - cy
            val n = 72
            val out = FloatArray((n + 1) * 2)
            for (i in 0..n) {
                val t = 2.0 * Math.PI * i / n
                val c = Math.cos(t).toFloat(); val sn = Math.sin(t).toFloat()
                out[i * 2] = cx + c * ax + sn * bx
                out[i * 2 + 1] = cy + c * ay + sn * by
            }
            out
        }
    }

    override fun recomputeBounds() {
        val o = outline()
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (i in o.indices step 2) { a = min(a, o[i]); c = max(c, o[i]); b = min(b, o[i + 1]); d = max(d, o[i + 1]) }
        val pad = SHAPE_WIDTH * widthScale / 2f + 1f + if (kind == ShapeKind.ARROW) 14f else 0f
        minX = a - pad; minY = b - pad; maxX = c + pad; maxY = d + pad
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean {
        val o = outline()
        for (i in 0 until o.size / 2 - 1) {
            if (segDist(x, y, o[i * 2], o[i * 2 + 1], o[i * 2 + 2], o[i * 2 + 3]) <= r + SHAPE_WIDTH * widthScale / 2f) return true
        }
        return false
    }

    override fun transform(m: Matrix) {
        m.mapPoints(pts)
        widthScale *= m.mapRadius(1f)
        recomputeBounds()
    }

    override fun copy(): Item = ShapeItem(kind, pts.copyOf(), closed, gray, widthScale)

    override fun fractionInside(poly: FloatArray): Float {
        val o = outline()
        val n = o.size / 2
        var inside = 0
        for (i in 0 until n) if (pointInPolygon(o[i * 2], o[i * 2 + 1], poly)) inside++
        return inside / n.toFloat()
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val qx = ax + t * dx; val qy = ay + t * dy
        return sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
    }
}

// ---------------------------------------------------------------------------------------------
// Text boxes
// ---------------------------------------------------------------------------------------------

/** Typed text in a box. [x],[y] is the top-left corner, [boxW] the wrapping width, [size] the font size, all in page units. */
class TextItem(
    text: String, var x: Float, var y: Float, var boxW: Float, var size: Float,
    var gray: Int = 0, var bold: Boolean = false
) : Item() {
    var text: String = text
        set(v) { field = v; layoutCache = null; recomputeBounds() }

    private var layoutCache: StaticLayout? = null
    private var cachedKey = ""

    init { recomputeBounds() }

    fun layout(): StaticLayout {
        val key = "$text|$boxW|$size|$bold|$gray"
        layoutCache?.let { if (key == cachedKey) return it }
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(gray, gray, gray)
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }
        val l = StaticLayout.Builder.obtain(text.ifEmpty { " " }, 0, text.ifEmpty { " " }.length, paint, max(boxW.toInt(), 20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
        layoutCache = l; cachedKey = key
        return l
    }

    override fun recomputeBounds() {
        layoutCache = null
        minX = x; minY = y; maxX = x + boxW; maxY = y + layout().height.toFloat()
    }

    override fun hit(x: Float, y: Float, r: Float) = x >= minX - r && x <= maxX + r && y >= minY - r && y <= maxY + r

    override fun transform(m: Matrix) {
        val p = floatArrayOf(x, y, x + boxW, y)
        m.mapPoints(p)
        x = min(p[0], p[2]); y = p[1]
        boxW = kotlin.math.abs(p[2] - p[0]).coerceAtLeast(30f)
        size = (size * m.mapRadius(1f)).coerceIn(8f, 400f)
        recomputeBounds()
    }

    override fun copy(): Item = TextItem(text, x, y, boxW, size, gray, bold)

    override fun fractionInside(poly: FloatArray): Float {
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val pts = floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, cx, cy)
        var inside = 0
        for (i in 0 until 5) if (pointInPolygon(pts[i * 2], pts[i * 2 + 1], poly)) inside++
        return inside / 5f
    }
}

// ---------------------------------------------------------------------------------------------
// Images
// ---------------------------------------------------------------------------------------------

/** A picture stored in the app ([file]) placed in the rectangle (l,t)-(r,b) in page units. */
class ImageItem(val file: String, var l: Float, var t: Float, var r: Float, var b: Float, val srcW: Int, val srcH: Int) : Item() {
    init { recomputeBounds() }

    override fun recomputeBounds() { minX = min(l, r); maxX = max(l, r); minY = min(t, b); maxY = max(t, b) }

    override fun hit(x: Float, y: Float, r: Float) = x >= minX - r && x <= maxX + r && y >= minY - r && y <= maxY + r

    override fun transform(m: Matrix) {
        val p = floatArrayOf(l, t, r, b)
        m.mapPoints(p)
        l = p[0]; t = p[1]; r = p[2]; b = p[3]
        recomputeBounds()
    }

    override fun copy(): Item = ImageItem(file, l, t, r, b, srcW, srcH)

    override fun fractionInside(poly: FloatArray): Float {
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val pts = floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, cx, cy)
        var inside = 0
        for (i in 0 until 5) if (pointInPolygon(pts[i * 2], pts[i * 2 + 1], poly)) inside++
        return inside / 5f
    }
}

// ---------------------------------------------------------------------------------------------
// Tape: a patch that hides what is under it (for studying). Tap it with the Tape tool to lift or put it back.
// ---------------------------------------------------------------------------------------------

class TapeItem(var l: Float, var t: Float, var r: Float, var b: Float, var covered: Boolean = true) : Item() {
    init { recomputeBounds() }

    override fun recomputeBounds() { minX = min(l, r); maxX = max(l, r); minY = min(t, b); maxY = max(t, b) }

    override fun hit(x: Float, y: Float, r: Float) = x >= minX - r && x <= maxX + r && y >= minY - r && y <= maxY + r

    override fun transform(m: Matrix) {
        val p = floatArrayOf(l, t, r, b)
        m.mapPoints(p)
        l = p[0]; t = p[1]; r = p[2]; b = p[3]
        recomputeBounds()
    }

    override fun copy(): Item = TapeItem(l, t, r, b, covered)

    override fun fractionInside(poly: FloatArray): Float {
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val pts = floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, cx, cy)
        var inside = 0
        for (i in 0 until 5) if (pointInPolygon(pts[i * 2], pts[i * 2 + 1], poly)) inside++
        return inside / 5f
    }
}

// ---------------------------------------------------------------------------------------------
// Pages and notebooks
// ---------------------------------------------------------------------------------------------

class PageData(var paper: Paper = Paper.RULED) {
    val items = ArrayList<Item>()

    /** Page size in page units. Blank pages use the tablet's proportions; PDF pages use the PDF's own. */
    var w: Float = PAGE_W
    var h: Float = PAGE_H

    /** When set, the page background is page [pdfPage] (0-based) of this stored PDF file. */
    var pdfFile: String? = null
    var pdfPage: Int = 0

    val isPdf get() = pdfFile != null

    /** A page marked for quick return (shown with a star in the Pages screen). */
    var bookmarked = false

    /** Distance between ruled lines / grid squares / dots, in page units (64 is about 9.5 mm on A4). */
    var spacing = 64f
}

/** An empty page with the same paper, spacing and proportions as [src] (PDF pages give a plain page). */
fun blankPageLike(src: PageData): PageData = PageData(if (src.isPdf) Paper.BLANK else src.paper).also {
    it.spacing = src.spacing
    if (!src.isPdf) it.h = it.w * src.h / src.w
}

/** One audio recording of a notebook. [file] is a name inside the app's audio folder. */
class Recording(val id: Int, val file: String, val startedAt: Long, var durationMs: Int)

class NoteData(var title: String = "Notebook", var created: Long = System.currentTimeMillis()) {
    val pages = ArrayList<PageData>()
    val recordings = ArrayList<Recording>()
    var modified: Long = created

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

// ---------------------------------------------------------------------------------------------
// Undo / redo
// ---------------------------------------------------------------------------------------------

sealed class Cmd(val page: PageData) {
    class Add(page: PageData, val items: List<Item>) : Cmd(page)

    /** [removed] is in the order the items were taken out; each index was valid at that moment. */
    class Remove(page: PageData, val removed: List<Pair<Int, Item>>) : Cmd(page)

    /** [m] has already been applied to [items]; undo applies its inverse. */
    class Transform(page: PageData, val items: List<Item>, val m: Matrix) : Cmd(page)

    /** Anything else: the page's items before and after (for partial erasing), or a custom edit (text). */
    class Custom(page: PageData, val undoAction: () -> Unit, val redoAction: () -> Unit) : Cmd(page)
}
