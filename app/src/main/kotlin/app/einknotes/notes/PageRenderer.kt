package app.einknotes.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws pages. Used by the on-screen view, by thumbnails and by PDF export, so a page looks identical everywhere.
 * All draw calls take a transform: view = page * scale + (tx, ty).
 */
class PageRenderer {

    private val linePaint = Paint().apply { color = Color.rgb(190, 190, 190); strokeWidth = 1.5f; isAntiAlias = false }
    private val borderPaint = Paint().apply { color = Color.rgb(120, 120, 120); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val dotPaint = Paint().apply { color = Color.rgb(150, 150, 150); isAntiAlias = true }
    private val inkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlighterPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
        // Multiply keeps black text black underneath a grey highlight, and a single path never darkens itself.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }

    // ---- paper -------------------------------------------------------------------------------

    fun drawPaper(c: Canvas, page: PageData, scale: Float, tx: Float, ty: Float) {
        val l = tx; val t = ty; val r = tx + page.w * scale; val b = ty + page.h * scale
        val step = 64f * scale
        when (page.paper) {
            Paper.BLANK -> { }
            Paper.RULED -> {
                var y = t + 120f * scale
                while (y < b - 20f * scale) { c.drawLine(l + 40f * scale, y, r - 40f * scale, y, linePaint); y += step }
            }
            Paper.GRID -> {
                var y = t + step
                while (y < b) { c.drawLine(l, y, r, y, linePaint); y += step }
                var x = l + step
                while (x < r) { c.drawLine(x, t, x, b, linePaint); x += step }
            }
            Paper.DOTS -> {
                var y = t + step
                while (y < b) {
                    var x = l + step
                    while (x < r) { c.drawCircle(x, y, 2.2f * max(scale, 0.6f), dotPaint); x += step }
                    y += step
                }
            }
        }
        drawBorder(c, page, scale, tx, ty)
    }

    fun drawBorder(c: Canvas, page: PageData, scale: Float, tx: Float, ty: Float) {
        c.drawRect(tx, ty, tx + page.w * scale, ty + page.h * scale, borderPaint)
    }

    // ---- items -------------------------------------------------------------------------------

    /** Draws every item of the page in order, skipping those in [skip] (used while items are being dragged). */
    fun drawItems(c: Canvas, page: PageData, scale: Float, tx: Float, ty: Float, skip: Collection<Item>? = null) {
        for (item in page.items) {
            if (skip != null && skip.any { it === item }) continue
            drawItem(c, item, scale, tx, ty)
        }
    }

    fun drawItem(c: Canvas, item: Item, scale: Float, tx: Float, ty: Float) {
        when (item) {
            is Stroke -> drawStroke(c, item, scale, tx, ty)
        }
    }

    private fun drawStroke(c: Canvas, s: Stroke, scale: Float, tx: Float, ty: Float) {
        if (s.brush == Brush.HIGHLIGHTER) {
            drawHighlighter(c, highlighterPath(s.xs, s.ys, s.n, scale, tx, ty), s.gray, HIGHLIGHTER_WIDTH * s.widthScale * scale, s.n == 1)
            return
        }
        val p = newPainter(c, s.brush, s.gray, s.widthScale, s.nibAngle, s.nibFlat, scale)
        for (i in 0 until s.n) p.add(tx + s.xs[i] * scale, ty + s.ys[i] * scale, s.ps[i])
        p.finish()
    }

    // ---- highlighter -------------------------------------------------------------------------

    /** Smooth path through the points (view coordinates) using the same midpoint curves as the pen. */
    fun highlighterPath(xs: FloatArray, ys: FloatArray, n: Int, scale: Float, tx: Float, ty: Float): Path {
        val path = Path()
        if (n == 0) return path
        path.moveTo(tx + xs[0] * scale, ty + ys[0] * scale)
        if (n == 1) { path.lineTo(tx + xs[0] * scale + 0.1f, ty + ys[0] * scale); return path }
        for (i in 1 until n) {
            val px = tx + xs[i - 1] * scale; val py = ty + ys[i - 1] * scale
            val cx = tx + xs[i] * scale; val cy = ty + ys[i] * scale
            path.quadTo(px, py, (px + cx) / 2f, (py + cy) / 2f)
        }
        path.lineTo(tx + xs[n - 1] * scale, ty + ys[n - 1] * scale)
        return path
    }

    fun drawHighlighter(c: Canvas, path: Path, gray: Int, widthPx: Float, dot: Boolean) {
        highlighterPaint.color = Color.rgb(gray, gray, gray)
        highlighterPaint.strokeWidth = max(widthPx, 2f)
        highlighterPaint.strokeCap = if (dot) Paint.Cap.ROUND else Paint.Cap.SQUARE
        c.drawPath(path, highlighterPaint)
    }

    // ---- pen / calligraphy -------------------------------------------------------------------

    fun newPainter(c: Canvas, brush: Brush, gray: Int, widthScale: Float, nibAngle: Float, nibFlat: Float, scale: Float) =
        StrokePainter(c, inkPaint, brush, gray, widthScale, nibAngle, nibFlat, scale)

    /**
     * Turns a stream of points into smooth, variable-width ink. Between the midpoints of neighbouring
     * samples it draws a curve whose control point is the sample itself (the standard quadratic
     * smoothing). It is used both for live ink and for redrawing saved strokes, so they look identical.
     * For the calligraphy brush the width also follows the direction of travel relative to the nib angle.
     */
    class StrokePainter(
        private val canvas: Canvas,
        private val paint: Paint,
        private val brush: Brush,
        gray: Int,
        private val widthScale: Float,
        private val nibAngle: Float,
        private val nibFlat: Float,
        private val scale: Float
    ) {
        private val color = Color.rgb(gray, gray, gray)
        private var count = 0
        private var ex = 0f; private var ey = 0f; private var ew = 0f   // where drawing has reached
        private var cx = 0f; private var cy = 0f; private var cw = 0f   // latest raw point
        var dirty: Rect? = null

        private fun widthFor(pressure: Float): Float = when (brush) {
            Brush.CALLIGRAPHY -> (1.6f + 4.4f * pressure.coerceIn(0f, 1f).pow(0.8f)) * widthScale * scale
            else -> penWidthFor(pressure) * widthScale * scale
        }

        fun add(x: Float, y: Float, pressure: Float) {
            val w = widthFor(pressure)
            paint.color = color
            if (count == 0) {
                cx = x; cy = y; cw = w; ex = x; ey = y; ew = w
                paint.strokeWidth = max(w, 1.2f)
                canvas.drawPoint(x, y, paint)
                grow(x, y, w)
            } else {
                val mx = (cx + x) / 2f; val my = (cy + y) / 2f; val mw = (cw + w) / 2f
                if (count == 1) line(ex, ey, ew, mx, my, mw) else quad(ex, ey, ew, cx, cy, mx, my, mw)
                ex = mx; ey = my; ew = mw; cx = x; cy = y; cw = w
            }
            count++
        }

        fun finish() { if (count >= 2) { paint.color = color; line(ex, ey, ew, cx, cy, cw) } }

        private fun line(x0: Float, y0: Float, w0: Float, x1: Float, y1: Float, w1: Float) {
            val len = dist(x0, y0, x1, y1)
            val n = min(24, (len / 5f).toInt() + 1)
            var px = x0; var py = y0; var pw = w0
            for (i in 1..n) {
                val t = i / n.toFloat()
                val x = x0 + (x1 - x0) * t; val y = y0 + (y1 - y0) * t; val w = w0 + (w1 - w0) * t
                seg(px, py, pw, x, y, w); px = x; py = y; pw = w
            }
        }

        private fun quad(x0: Float, y0: Float, w0: Float, qx: Float, qy: Float, x2: Float, y2: Float, w2: Float) {
            val len = dist(x0, y0, qx, qy) + dist(qx, qy, x2, y2)
            val n = min(30, ceil(len / 3f).toInt().coerceAtLeast(1))
            var px = x0; var py = y0; var pw = w0
            for (i in 1..n) {
                val t = i / n.toFloat(); val u = 1f - t
                val x = u * u * x0 + 2f * u * t * qx + t * t * x2
                val y = u * u * y0 + 2f * u * t * qy + t * t * y2
                val w = w0 + (w2 - w0) * t
                seg(px, py, pw, x, y, w); px = x; py = y; pw = w
            }
        }

        private fun seg(x0: Float, y0: Float, w0: Float, x1: Float, y1: Float, w1: Float) {
            var w = max((w0 + w1) / 2f, 1.2f)
            if (brush == Brush.CALLIGRAPHY) {
                // broad-edge pen: thinnest when moving along the nib edge, thickest across it
                val theta = atan2(y1 - y0, x1 - x0)
                val k = (1f - nibFlat) + nibFlat * abs(sin(theta - nibAngle))
                w = max(w * (0.35f + 0.65f * k) * 1.25f, 1.2f)
            }
            paint.strokeWidth = w
            canvas.drawLine(x0, y0, x1, y1, paint)
            grow(x0, y0, w); grow(x1, y1, w)
        }

        private fun grow(x: Float, y: Float, w: Float) {
            val pad = (w / 2f).toInt() + 2
            val r = Rect(x.toInt() - pad, y.toInt() - pad, x.toInt() + pad, y.toInt() + pad)
            dirty = dirty?.also { it.union(r) } ?: r
        }

        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }
    }
}
