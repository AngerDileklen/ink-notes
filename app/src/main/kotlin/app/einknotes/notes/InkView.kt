package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One notebook page on screen. Everything committed (paper + strokes) lives in a single bitmap that is
 * only rebuilt when the page, zoom, pan, erase or undo changes. New ink is drawn straight onto that
 * bitmap one small piece at a time, so each pen sample only repaints a few pixels. That keeps e-ink
 * updates small, which is what makes writing feel fast.
 */
class InkView(context: Context, val note: NoteData, private val host: Host) : View(context) {

    interface Host {
        /** Pen touched the glass: switch the panel to a fast waveform. */
        fun onPenDown()
        /** Pen left the glass: switch back to a clean waveform soon. */
        fun onPenUp()
        /** Strokes, pages or paper changed: save, refresh labels and the undo buttons. */
        fun onDocumentChanged()
    }

    // ---- state -------------------------------------------------------------------------------

    var pageIndex = 0
        private set
    var tool = Tool.PEN
    /** What the pen's side button does while it is held. */
    var sideButtonTool = Tool.ERASER
    /** Until this time (uptimeMillis) "Virtual" pen input is treated as the firmware's fake page-turn swipe. */
    private var ignoreVirtualUntil = 0L

    /** Called when a volume button is pressed: the firmware then injects a fake swipe from a "Virtual" device. */
    fun ignoreVirtualFor(ms: Long) { ignoreVirtualUntil = android.os.SystemClock.uptimeMillis() + ms }

    fun describe(): String =
        "page=${pageIndex + 1}/${note.pages.size} strokes=${page.strokes.size} tool=$tool zoomIndex=$zoomIndex scale=${"%.3f".format(scale)} " +
            "paper=${page.paper} undo=${undoStack.size} redo=${redoStack.size} view=${width}x${height}"

    val page: PageData get() = note.pages[pageIndex]

    private val zoomSteps = floatArrayOf(1f, 1.5f, 2f, 3f)
    var zoomIndex = 0
        private set
    private var fit = 1f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var bitmap: Bitmap? = null
    private var bmpCanvas: Canvas? = null

    private val inkPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaint = Paint().apply { color = Color.rgb(190, 190, 190); strokeWidth = 1.5f; isAntiAlias = false }
    private val borderPaint = Paint().apply { color = Color.rgb(120, 120, 120); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val dotPaint = Paint().apply { color = Color.rgb(150, 150, 150); isAntiAlias = true }

    // undo / redo
    private val undoStack = ArrayList<Cmd>()
    private val redoStack = ArrayList<Cmd>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // the stroke being written right now
    private var live: Smoother? = null
    private val liveXs = FloatList()
    private val liveYs = FloatList()
    private val livePs = FloatList()
    private var lastPressure = 0f
    private var lastVx = 0f
    private var lastVy = 0f

    // the erase gesture being performed right now
    private var erasing = false
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private val erased = ArrayList<Pair<Int, Stroke>>()
    private var rebuildPending = false

    // hand (pan) gesture
    private var panning = false
    private var panLastX = 0f
    private var panLastY = 0f
    private var lastPanRebuild = 0L

    /** Area drawn since the panel last got a "clean" pass, in view pixels. Used by the polish step. */
    private var polish: Rect? = null

    // ---- public actions ----------------------------------------------------------------------

    fun goToPage(i: Int) {
        if (i !in note.pages.indices || i == pageIndex) return
        pageIndex = i
        fitToView(keepZoom = true)
        rebuild()
    }

    fun addPageAfterCurrent() {
        note.pages.add(pageIndex + 1, PageData(page.paper))
        pageIndex += 1
        fitToView(keepZoom = false)
        rebuild()
        host.onDocumentChanged()
    }

    fun setPaper(p: Paper) {
        page.paper = p
        rebuild()
        host.onDocumentChanged()
    }

    fun zoomBy(step: Int) {
        val next = (zoomIndex + step).coerceIn(0, zoomSteps.size - 1)
        if (next == zoomIndex) return
        // keep the page point under the middle of the view fixed
        val cx = width / 2f; val cy = height / 2f
        val px = (cx - tx) / scale; val py = (cy - ty) / scale
        zoomIndex = next
        scale = fit * zoomSteps[zoomIndex]
        tx = cx - px * scale; ty = cy - py * scale
        clampOffsets()
        rebuild()
    }

    fun panBy(dxFraction: Float, dyFraction: Float) {
        tx -= dxFraction * width; ty -= dyFraction * height
        clampOffsets()
        rebuild()
    }

    fun undo() {
        val cmd = undoStack.removeLastOrNull() ?: return
        when (cmd) {
            is Cmd.Add -> cmd.page.strokes.remove(cmd.stroke)
            is Cmd.Remove -> for ((idx, s) in cmd.removed.asReversed()) cmd.page.strokes.add(min(idx, cmd.page.strokes.size), s)
        }
        redoStack.add(cmd)
        showPageOf(cmd)
    }

    fun redo() {
        val cmd = redoStack.removeLastOrNull() ?: return
        when (cmd) {
            is Cmd.Add -> cmd.page.strokes.add(cmd.stroke)
            is Cmd.Remove -> for ((idx, _) in cmd.removed) if (idx < cmd.page.strokes.size) cmd.page.strokes.removeAt(idx)
        }
        undoStack.add(cmd)
        showPageOf(cmd)
    }

    /** The area that needs a clean repaint after fast writing, or null. Resets it. */
    fun takePolishRect(): Rect? { val r = polish; polish = null; return r }

    fun repaintAll() { rebuild() }

    // ---- layout / transform ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpCanvas = Canvas(bitmap!!)
        fitToView(keepZoom = true)
        rebuild()
    }

    private fun fitToView(keepZoom: Boolean) {
        if (width <= 0 || height <= 0) return
        fit = min(width / PAGE_W, height / PAGE_H)
        if (!keepZoom) zoomIndex = 0
        scale = fit * zoomSteps[zoomIndex]
        tx = 0f; ty = 0f
        clampOffsets()
    }

    private fun clampOffsets() {
        val pw = PAGE_W * scale; val ph = PAGE_H * scale
        tx = if (pw <= width) (width - pw) / 2f else tx.coerceIn(width - pw, 0f)
        ty = if (ph <= height) (height - ph) / 2f else ty.coerceIn(height - ph, 0f)
    }

    private fun showPageOf(cmd: Cmd) {
        val idx = note.pages.indexOf(cmd.page)
        if (idx >= 0 && idx != pageIndex) { pageIndex = idx; fitToView(keepZoom = true) }
        rebuild()
        host.onDocumentChanged()
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /** Redraws paper and every stroke into the bitmap, then repaints the whole view. */
    private fun rebuild() {
        val c = bmpCanvas ?: return
        rebuildPending = false
        c.drawColor(Color.WHITE)
        drawPaper(c)
        for (s in page.strokes) drawStroke(c, s)
        invalidate()
    }

    private fun scheduleRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        postDelayed({ if (rebuildPending) rebuild() }, 90)
    }

    private fun drawPaper(c: Canvas) {
        val l = tx; val t = ty; val r = tx + PAGE_W * scale; val b = ty + PAGE_H * scale
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
        c.drawRect(l, t, r, b, borderPaint)
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val sm = Smoother(c)
        for (i in 0 until s.n) sm.add(tx + s.xs[i] * scale, ty + s.ys[i] * scale, penWidthFor(s.ps[i]) * scale)
        sm.finish()
    }

    /**
     * Turns a stream of points into smooth, variable-width ink. Between the midpoints of neighbouring
     * samples it draws a curve whose control point is the sample itself (the standard quadratic
     * smoothing). It is used both for live ink and for redrawing saved strokes, so they look identical.
     */
    private inner class Smoother(val canvas: Canvas) {
        private var count = 0
        private var ex = 0f; private var ey = 0f; private var ew = 0f   // where drawing has reached
        private var cx = 0f; private var cy = 0f; private var cw = 0f   // latest raw point
        var dirty: Rect? = null

        fun add(x: Float, y: Float, w: Float) {
            if (count == 0) {
                cx = x; cy = y; cw = w; ex = x; ey = y; ew = w
                inkPaint.strokeWidth = max(w, 1.2f)
                canvas.drawPoint(x, y, inkPaint)
                grow(x, y, w)
            } else {
                val mx = (cx + x) / 2f; val my = (cy + y) / 2f; val mw = (cw + w) / 2f
                if (count == 1) line(ex, ey, ew, mx, my, mw) else quad(ex, ey, ew, cx, cy, mx, my, mw)
                ex = mx; ey = my; ew = mw; cx = x; cy = y; cw = w
            }
            count++
        }

        fun finish() { if (count >= 2) line(ex, ey, ew, cx, cy, cw) }

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
            val w = max((w0 + w1) / 2f, 1.2f)
            inkPaint.strokeWidth = w
            canvas.drawLine(x0, y0, x1, y1, inkPaint)
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

    // ---- input -------------------------------------------------------------------------------

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (debuggable && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP)) {
            Log.d("InkView", "touch action=${e.actionMasked} tool=${e.getToolType(0)} deviceId=${e.deviceId} " +
                "dev=${e.device?.name} source=0x${Integer.toHexString(e.source)} p=${e.pressure} xy=${e.x.toInt()},${e.y.toInt()}")
        }
        // The firmware injects a fake swipe (device "Virtual") right after a volume button. Never draw that one.
        // Other "Virtual" pen input is real: when an accessibility service is active, Android relabels the real pen.
        val virtual = e.device?.name == "Virtual" || e.deviceId < 0
        if (virtual && android.os.SystemClock.uptimeMillis() < ignoreVirtualUntil) return true
        val toolType = e.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) return true   // this tablet has no finger touch; ignore strays

        val active = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            toolType == 6 -> sideButtonTool                          // pen tool type while the side button is held
            else -> tool
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.onPenDown()
                lastVx = e.x; lastVy = e.y
                when (active) {
                    Tool.PEN -> beginStroke(e.x, e.y, e.pressure)
                    Tool.ERASER -> { erasing = true; erased.clear(); eraseLastX = e.x; eraseLastY = e.y; eraseAt(e.x, e.y) }
                    Tool.HAND -> { panning = true; panLastX = e.x; panLastY = e.y }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) move(active, e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
                move(active, e.x, e.y, e.pressure)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when {
                    live != null -> {
                        // The lift-off position is part of the stroke: without it every line ends a little short.
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            for (i in 0 until e.historySize) move(active, e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
                            finalPoint(e.x, e.y)
                        }
                        endStroke()
                    }
                    erasing -> endErase()
                    panning -> { panning = false; rebuild() }
                }
                host.onPenUp()
            }
        }
        return true
    }

    private fun move(active: Tool, x: Float, y: Float, rawPressure: Float) {
        when {
            live != null && active == Tool.PEN -> addStrokePoint(x, y, rawPressure)
            erasing -> eraseAlong(x, y)
            panning -> {
                tx += x - panLastX; ty += y - panLastY
                panLastX = x; panLastY = y
                clampOffsets()
                val now = System.currentTimeMillis()
                if (now - lastPanRebuild > 140) { lastPanRebuild = now; rebuild() }
            }
        }
    }

    // ---- writing -----------------------------------------------------------------------------

    private fun beginStroke(x: Float, y: Float, rawPressure: Float) {
        val c = bmpCanvas ?: return
        liveXs.clear(); liveYs.clear(); livePs.clear()
        live = Smoother(c)
        lastPressure = normalisePressure(rawPressure)
        pushPoint(x, y, lastPressure)
        flushLive()
    }

    private fun addStrokePoint(x: Float, y: Float, rawPressure: Float) {
        // Ignore jitter: samples closer than one pixel add nothing but cost time.
        val dx = x - lastVx; val dy = y - lastVy
        if (dx * dx + dy * dy < 1f) return
        lastVx = x; lastVy = y
        lastPressure = 0.6f * lastPressure + 0.4f * normalisePressure(rawPressure)   // gentle pressure smoothing
        pushPoint(x, y, lastPressure)
        flushLive()
    }

    /** Adds the pen-up position using the last known pressure (a lifted pen reports zero). */
    private fun finalPoint(x: Float, y: Float) {
        val dx = x - lastVx; val dy = y - lastVy
        if (dx * dx + dy * dy < 0.25f) return
        lastVx = x; lastVy = y
        pushPoint(x, y, lastPressure)
        flushLive()
    }

    private fun pushPoint(x: Float, y: Float, p: Float) {
        liveXs.add((x - tx) / scale); liveYs.add((y - ty) / scale); livePs.add(p)
        live?.add(x, y, penWidthFor(p) * scale)
    }

    /** Repaints just the little area the newest pieces of ink touched. */
    private fun flushLive() {
        val d = live?.dirty ?: return
        live?.dirty = null
        invalidate(d)
        polish = polish?.also { it.union(d) } ?: Rect(d)
    }

    private fun endStroke() {
        val sm = live ?: return
        sm.finish()
        flushLive()
        live = null
        if (liveXs.size >= 1) {
            val s = Stroke(Tool.PEN, liveXs.toArray(), liveYs.toArray(), livePs.toArray())
            page.strokes.add(s)
            undoStack.add(Cmd.Add(page, s))
            redoStack.clear()
            host.onDocumentChanged()
        }
    }

    // ---- erasing -----------------------------------------------------------------------------

    /** Erases along the straight path from the previous sample, so fast movement cannot jump over a stroke. */
    private fun eraseAlong(vx: Float, vy: Float) {
        val dx = vx - eraseLastX; val dy = vy - eraseLastY
        val dist = sqrt(dx * dx + dy * dy)
        val steps = max(1, (dist / 8f).toInt())
        for (i in 1..steps) eraseAt(eraseLastX + dx * i / steps, eraseLastY + dy * i / steps)
        eraseLastX = vx; eraseLastY = vy
    }

    private fun eraseAt(vx: Float, vy: Float) {
        val px = (vx - tx) / scale; val py = (vy - ty) / scale
        val r = 18f / scale
        val list = page.strokes
        var removedAny = false
        for (i in list.indices.reversed()) {
            val s = list[i]
            if (s.near(px, py, r) && s.hit(px, py, r)) {
                erased.add(i to s)
                list.removeAt(i)
                removedAny = true
            }
        }
        if (removedAny) scheduleRebuild()
    }

    private fun endErase() {
        erasing = false
        if (erased.isNotEmpty()) {
            undoStack.add(Cmd.Remove(page, erased.toList()))
            redoStack.clear()
            erased.clear()
            rebuild()
            host.onDocumentChanged()
        }
    }
}
