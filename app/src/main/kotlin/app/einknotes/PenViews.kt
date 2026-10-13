package app.einknotes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** What a drawing surface must offer to the activity. */
interface PenSurface {
    val modeName: String
    fun clearInk()
    fun asView(): View
}

/** Persistent off-screen page. Strokes are drawn here once; the screen only shows regions of it. */
class InkBitmap {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val pen = Paint().apply {
        isAntiAlias = false          // e-ink: aliased edges are crisper and cheaper
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    fun ensure(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val b = bitmap
        if (b != null && b.width == w && b.height == h) return
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(nb)
        c.drawColor(Color.WHITE)
        if (b != null) c.drawBitmap(b, 0f, 0f, null)
        bitmap = nb
        canvas = c
    }

    fun clear() {
        canvas?.drawColor(Color.WHITE)
    }

    val bmp: Bitmap? get() = bitmap

    /** Draws one segment and returns the changed rectangle. */
    fun segment(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int): Rect {
        pen.color = color
        pen.strokeWidth = width
        canvas?.drawLine(x0, y0, x1, y1, pen)
        val pad = (width / 2f).toInt() + 2
        return Rect(
            min(x0, x1).toInt() - pad, min(y0, y1).toInt() - pad,
            max(x0, x1).toInt() + pad, max(y0, y1).toInt() + pad
        )
    }
}

/**
 * Turns MotionEvents into ink. Stylus tip draws, eraser end erases, side button draws grey so we can
 * see that it was detected. Returns the changed region, or null if nothing was drawn.
 */
class PenLogic(private val stats: Stats, private val ink: InkBitmap) {
    private var lastX = 0f
    private var lastY = 0f
    private var active = false

    fun handle(e: MotionEvent): Rect? {
        val tool = e.getToolType(0)
        val isEraser = tool == MotionEvent.TOOL_TYPE_ERASER
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS
        if (!isEraser && !isStylus) return null   // finger / mouse: logged elsewhere, not drawn

        var dirty: Rect? = null
        fun add(r: Rect) { dirty = dirty?.also { it.union(r) } ?: r }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                lastX = e.x; lastY = e.y
                add(dot(e, isEraser))
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                // Batched samples first (this is where high sample-rate pens hide extra points).
                for (i in 0 until e.historySize) {
                    val x = e.getHistoricalX(i); val y = e.getHistoricalY(i)
                    add(seg(lastX, lastY, x, y, e.getHistoricalPressure(i), e.buttonState, isEraser))
                    lastX = x; lastY = y
                }
                add(seg(lastX, lastY, e.x, e.y, e.pressure, e.buttonState, isEraser))
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = false
        }
        return dirty
    }

    private fun width(p: Float, eraser: Boolean) =
        if (eraser) 28f + 36f * p else 1.5f + 9f * p

    private fun colorFor(button: Int, eraser: Boolean) = when {
        eraser -> Color.WHITE
        button and android.view.MotionEvent.BUTTON_STYLUS_PRIMARY != 0 -> Color.rgb(110, 110, 110)
        else -> Color.BLACK
    }

    private fun seg(x0: Float, y0: Float, x1: Float, y1: Float, p: Float, button: Int, eraser: Boolean) =
        ink.segment(x0, y0, x1, y1, width(p, eraser), colorFor(button, eraser))

    private fun dot(e: MotionEvent, eraser: Boolean) =
        ink.segment(e.x, e.y, e.x + 0.1f, e.y + 0.1f, width(e.pressure, eraser), colorFor(e.buttonState, eraser))
}

/** Path A and B: ordinary View. [dirtyOnly] decides whether we ask Android to redraw only the changed rect. */
class PenView(
    context: Context,
    private val stats: Stats,
    private val dirtyOnly: Boolean,
    private val onLog: (String) -> Unit
) : View(context), PenSurface {

    private val ink = InkBitmap()
    private val logic = PenLogic(stats, ink)
    private var lastEventTime = 0L

    override val modeName get() = if (dirtyOnly) "VIEW dirty-rect" else "VIEW full-redraw"
    override fun clearInk() { ink.clear(); invalidate() }
    override fun asView(): View = this

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        ink.ensure(w, h)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        onLog(stats.onMotion(e, "touch"))
        lastEventTime = e.eventTime
        val dirty = logic.handle(e)
        if (dirty != null) {
            if (dirtyOnly) invalidate(dirty) else invalidate()
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        onLog(stats.onMotion(e, "hover"))   // pen hovering above the glass (if the digitizer reports it)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        ink.bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (lastEventTime != 0L) stats.recordLatency(SystemClock.uptimeMillis() - lastEventTime)
    }
}

/** Path C: SurfaceView, pushing only the dirty rectangle of the page to the screen. */
class SurfacePenView(
    context: Context,
    private val stats: Stats,
    private val onLog: (String) -> Unit
) : SurfaceView(context), PenSurface, SurfaceHolder.Callback {

    private val ink = InkBitmap()
    private val logic = PenLogic(stats, ink)

    override val modeName get() = "SURFACE dirty-rect"
    override fun clearInk() { ink.clear(); present(null) }
    override fun asView(): View = this

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) { }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) { ink.ensure(w, hgt); present(null) }
    override fun surfaceDestroyed(h: SurfaceHolder) { }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        onLog(stats.onMotion(e, "touch"))
        val dirty = logic.handle(e)
        if (dirty != null) {
            present(dirty)
            stats.recordLatency(SystemClock.uptimeMillis() - e.eventTime)
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        onLog(stats.onMotion(e, "hover"))
        return true
    }

    private fun present(dirty: Rect?) {
        val bmp = ink.bmp ?: return
        val area = dirty?.also { it.intersect(0, 0, bmp.width, bmp.height) }
        val canvas = holder.lockCanvas(area) ?: return
        try {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
