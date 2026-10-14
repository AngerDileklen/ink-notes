package app.einknotes.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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
class PageRenderer(private val forExport: Boolean = false) {

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

    private val imagePaint = Paint().apply {
        isFilterBitmap = true
        // pictures are shown in grey: the panel has no colour
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    }

    private val majorPaint = Paint().apply { color = Color.rgb(140, 140, 140); strokeWidth = 2.2f; isAntiAlias = false }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 110, 110) }
    private val boxPaint = Paint().apply { color = Color.rgb(120, 120, 120); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val tapeFill = Paint().apply { color = Color.rgb(205, 205, 205); style = Paint.Style.FILL }
    private val tapeHatch = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 2f; isAntiAlias = false }
    private val tapeEdge = Paint().apply { color = Color.rgb(90, 90, 90); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val tapeLifted = Paint().apply {
        color = Color.rgb(140, 140, 140); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val exportHighlighterPaint = Paint().apply {
        isAntiAlias = true; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }

    // ---- paper -------------------------------------------------------------------------------

    fun drawPaper(c: Canvas, page: PageData, scale: Float, tx: Float, ty: Float) {
        val l = tx; val t = ty; val r = tx + page.w * scale; val b = ty + page.h * scale
        val step = page.spacing * scale
        val k = page.spacing / 64f
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
            Paper.NARROW -> {
                val st = 40f * k * scale
                var y = t + 100f * scale
                while (y < b - 20f * scale) { c.drawLine(l + 40f * scale, y, r - 40f * scale, y, linePaint); y += st }
            }
            Paper.CORNELL -> {
                val header = t + 0.09f * page.h * scale
                val summary = t + 0.82f * page.h * scale
                val cue = l + 0.28f * page.w * scale
                c.drawLine(l, header, r, header, linePaint)
                c.drawLine(l, summary, r, summary, linePaint)
                c.drawLine(cue, header, cue, summary, linePaint)
                var y = header + step
                while (y < summary - 10f * scale) { c.drawLine(cue + 18f * scale, y, r - 30f * scale, y, linePaint); y += step }
            }
            Paper.MUSIC -> {
                val gap = 15f * k * scale
                var top = t + 110f * scale
                while (top + 4 * gap < b - 40f * scale) {
                    for (k in 0..4) c.drawLine(l + 50f * scale, top + k * gap, r - 50f * scale, top + k * gap, linePaint)
                    top += 4 * gap + 110f * scale
                }
            }
            Paper.COLLEGE -> {
                var y = t + 120f * scale
                while (y < b - 20f * scale) { c.drawLine(l, y, r, y, linePaint); y += step }
                val m = l + 0.12f * page.w * scale
                c.drawLine(m, t, m, b, majorPaint)
                c.drawLine(l, t + 120f * scale - step, r, t + 120f * scale - step, majorPaint)
            }
            Paper.ENGINEERING -> {
                val fine = step / 2f
                var i = 0; var y = t
                while (y < b) { c.drawLine(l, y, r, y, if (i % 5 == 0) majorPaint else linePaint); y += fine; i++ }
                i = 0; var x = l
                while (x < r) { c.drawLine(x, t, x, b, if (i % 5 == 0) majorPaint else linePaint); x += fine; i++ }
            }
            Paper.HEX -> {
                val rad = step * 0.62f
                val w = rad * 1.7320508f; val rowH = rad * 1.5f
                var row = 0; var cy = t + rad
                while (cy < b + rad) {
                    var cx = l + (if (row % 2 == 0) w / 2f else w)
                    while (cx < r + w) {
                        for (k in 0 until 6) {
                            val a0 = Math.toRadians(60.0 * k - 30.0); val a1 = Math.toRadians(60.0 * (k + 1) - 30.0)
                            val x0 = cx + rad * Math.cos(a0).toFloat(); val y0 = cy + rad * Math.sin(a0).toFloat()
                            val x1 = cx + rad * Math.cos(a1).toFloat(); val y1 = cy + rad * Math.sin(a1).toFloat()
                            if (x0 in l..r && x1 in l..r && y0 in t..b && y1 in t..b) c.drawLine(x0, y0, x1, y1, linePaint)
                        }
                        cx += w
                    }
                    cy += rowH; row++
                }
            }
            Paper.ISOMETRIC -> {
                val rowH = step * 0.8660254f
                var row = 0; var y = t + rowH
                while (y < b) {
                    var x = l + (if (row % 2 == 0) step else step / 2f)
                    while (x < r) { c.drawCircle(x, y, 2.2f * max(scale, 0.6f), dotPaint); x += step }
                    y += rowH; row++
                }
            }
            Paper.TWO_COL, Paper.THREE_COL -> {
                val cols = if (page.paper == Paper.TWO_COL) 2 else 3
                val top = t + 0.07f * page.h * scale
                c.drawLine(l + 40f * scale, top, r - 40f * scale, top, majorPaint)
                for (k in 1 until cols) { val x = l + page.w * scale * k / cols; c.drawLine(x, top, x, b - 40f * scale, linePaint) }
            }
            Paper.MANUSCRIPT -> {
                val mid = step * 0.5f
                var y = t + 100f * scale
                while (y + 2 * step < b - 20f * scale) {
                    c.drawLine(l + 40f * scale, y, r - 40f * scale, y, linePaint)                     // ascender line
                    var x = l + 40f * scale
                    while (x < r - 40f * scale) { c.drawLine(x, y + mid, minOf(x + 10f * scale, r - 40f * scale), y + mid, linePaint); x += 20f * scale }   // dashed middle line
                    c.drawLine(l + 40f * scale, y + step, r - 40f * scale, y + step, majorPaint)       // baseline
                    y += step * 2.2f
                }
            }
            Paper.DAILY -> {
                val ml = l + 50f * scale; val mr = r - 50f * scale
                val split = l + page.w * scale * 0.62f
                labelPaint.textSize = 30f * scale
                c.drawText("Daily planner", ml, t + 90f * scale, labelPaint)
                c.drawText("Date", split + 20f * scale, t + 90f * scale, labelPaint)
                c.drawLine(split + 100f * scale, t + 92f * scale, mr, t + 92f * scale, linePaint)
                val top = t + 150f * scale
                c.drawText("Schedule", ml, top, labelPaint)
                var y = top + 30f * scale; var hour = 6
                val hStep = (b - 80f * scale - y) / 16f
                for (i in 0 until 16) {
                    c.drawLine(ml, y, split - 20f * scale, y, linePaint)
                    labelPaint.textSize = 22f * scale; c.drawText("%02d".format(hour + i), ml, y + hStep * 0.8f, labelPaint)
                    y += hStep
                }
                labelPaint.textSize = 30f * scale
                c.drawText("Top priorities", split + 20f * scale, top, labelPaint)
                var py = top + 70f * scale
                for (i in 0 until 4) { c.drawLine(split + 20f * scale, py, mr, py, linePaint); py += 60f * scale }
                c.drawText("To do", split + 20f * scale, py + 30f * scale, labelPaint)
                py += 100f * scale
                while (py < b - 80f * scale) { c.drawRect(split + 20f * scale, py - 26f * scale, split + 46f * scale, py, boxPaint); c.drawLine(split + 60f * scale, py, mr, py, linePaint); py += 56f * scale }
            }
            Paper.WEEKLY -> {
                val ml = l + 40f * scale; val mr = r - 40f * scale
                labelPaint.textSize = 30f * scale
                c.drawText("Weekly planner", ml, t + 80f * scale, labelPaint)
                val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Notes")
                val top = t + 120f * scale; val bottom = b - 40f * scale
                val cellH = (bottom - top) / 4f; val cellW = (mr - ml) / 2f
                labelPaint.textSize = 24f * scale
                for (i in 0 until 8) {
                    val cx = ml + (i % 2) * cellW; val cy = top + (i / 2) * cellH
                    c.drawRect(cx, cy, cx + cellW, cy + cellH, boxPaint)
                    c.drawText(days[i], cx + 14f * scale, cy + 34f * scale, labelPaint)
                    var ly = cy + 90f * scale
                    while (ly < cy + cellH - 10f * scale) { c.drawLine(cx + 14f * scale, ly, cx + cellW - 14f * scale, ly, linePaint); ly += step * 0.75f }
                }
            }
            Paper.MONTHLY -> {
                val ml = l + 40f * scale; val mr = r - 40f * scale
                labelPaint.textSize = 30f * scale
                c.drawText("Month", ml, t + 80f * scale, labelPaint)
                val top = t + 150f * scale; val bottom = b - 300f * scale
                val cw = (mr - ml) / 7f; val rh = (bottom - top) / 6f
                labelPaint.textSize = 22f * scale
                val dn = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
                for (i in 0 until 7) c.drawText(dn[i], ml + i * cw + 10f * scale, top - 12f * scale, labelPaint)
                for (rr in 0..6) c.drawLine(ml, top + rr * rh, mr, top + rr * rh, boxPaint)
                for (cc in 0..7) c.drawLine(ml + cc * cw, top, ml + cc * cw, bottom, boxPaint)
                labelPaint.textSize = 28f * scale
                c.drawText("Goals", ml, bottom + 60f * scale, labelPaint); c.drawText("Tasks", ml + (mr - ml) * 0.5f, bottom + 60f * scale, labelPaint)
                var gy = bottom + 100f * scale
                while (gy < b - 40f * scale) { c.drawLine(ml, gy, ml + (mr - ml) * 0.45f, gy, linePaint); c.drawLine(ml + (mr - ml) * 0.5f, gy, mr, gy, linePaint); gy += 50f * scale }
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
        if (forExport) return
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
            is ShapeItem -> drawShape(c, item, scale, tx, ty)
            is TextItem -> {
                c.save()
                c.translate(tx + item.x * scale, ty + item.y * scale)
                c.scale(scale, scale)
                item.layout().draw(c)
                c.restore()
            }
            is TapeItem -> drawTape(c, item, scale, tx, ty)
            is ImageItem -> {
                val bmp = ImageStore.bitmap(item.file, ((item.maxX - item.minX) * scale).toInt()) ?: return
                c.drawBitmap(bmp, null, RectF(tx + item.minX * scale, ty + item.minY * scale, tx + item.maxX * scale, ty + item.maxY * scale), imagePaint)
            }
        }
    }

    private fun drawTape(c: Canvas, t: TapeItem, scale: Float, tx: Float, ty: Float) {
        val r = RectF(tx + t.minX * scale, ty + t.minY * scale, tx + t.maxX * scale, ty + t.maxY * scale)
        if (!t.covered) { c.drawRect(r, tapeLifted); return }
        c.drawRect(r, tapeFill)
        c.save()
        c.clipRect(r)
        val step = 18f
        var x = r.left - r.height()
        while (x < r.right) { c.drawLine(x, r.bottom, x + r.height(), r.top, tapeHatch); x += step }
        c.restore()
        c.drawRect(r, tapeEdge)
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

    private fun drawShape(c: Canvas, s: ShapeItem, scale: Float, tx: Float, ty: Float) {
        inkPaint.color = Color.rgb(s.gray, s.gray, s.gray)
        inkPaint.strokeWidth = max(SHAPE_WIDTH * s.widthScale * scale, 1.2f)
        val o = s.outline()
        val path = Path()
        for (i in 0 until o.size / 2) {
            val x = tx + o[i * 2] * scale; val y = ty + o[i * 2 + 1] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (s.closed && s.kind != ShapeKind.ELLIPSE) path.close()
        c.drawPath(path, inkPaint)
        if (s.kind == ShapeKind.ARROW) {
            // two short strokes sweeping back from the head
            val x0 = s.pts[0]; val y0 = s.pts[1]; val x1 = s.pts[2]; val y1 = s.pts[3]
            val ang = atan2(y1 - y0, x1 - x0)
            val len = min(max(10f * s.widthScale * 3f, 24f), 46f)
            for (side in intArrayOf(-1, 1)) {
                val a = ang + Math.PI.toFloat() + side * 0.5f
                c.drawLine(tx + x1 * scale, ty + y1 * scale,
                    tx + (x1 + kotlin.math.cos(a) * len) * scale, ty + (y1 + sin(a) * len) * scale, inkPaint)
            }
        }
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
        if (forExport) {
            exportHighlighterPaint.color = Color.argb(255 - gray, 0, 0, 0)
            exportHighlighterPaint.strokeWidth = max(widthPx, 2f)
            exportHighlighterPaint.strokeCap = if (dot) Paint.Cap.ROUND else Paint.Cap.SQUARE
            c.drawPath(path, exportHighlighterPaint)
            return
        }
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
