package app.einknotes.notes.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The e-ink look: black on white, solid greys (never transparency, which the panel dithers), 3 px borders,
 * no ripple, no animation, no shadows, large targets. Everything here is drawn by hand so nothing moves.
 */
object Eink {
    const val BLACK = Color.BLACK
    const val WHITE = Color.WHITE
    val GREY = Color.rgb(120, 120, 120)
    val LIGHT = Color.rgb(215, 215, 215)
    const val BORDER = 3f
    const val RADIUS = 10f
    /** Pixel sizes for this 330 dpi panel (about 13 px per mm). */
    const val ROW_H = 112
    const val BAR_H = 100
}

fun Context.label(text: String, size: Float = 18f, bold: Boolean = false, color: Int = Eink.BLACK) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/**
 * A button that draws an icon (see [Icons]) and/or a short label. Selected = inverted. Disabled = grey and
 * ignores the pen. Long press is optional.
 */
@SuppressLint("ViewConstructor")
class EButton(ctx: Context, icon: String?, label: String?, private val onClick: (EButton) -> Unit) : View(ctx) {
    var icon: String? = icon
        set(v) { if (field != v) { field = v; invalidate() } }
    var label: String? = label
        set(v) { if (field != v) { field = v; invalidate() } }
    var selectedState = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var enabledState = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Draw a border (dialog buttons) or not (toolbar icons). */
    var bordered = true
    var textSize = 30f
    var onLongPress: ((EButton) -> Unit)? = null

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Eink.BORDER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val handler = Handler(Looper.getMainLooper())
    private var longFired = false
    private val longRunnable = Runnable { longFired = true; onLongPress?.invoke(this) }

    init { isClickable = true; minimumHeight = Eink.BAR_H }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.resolveSize(if (label != null) (text.apply { textSize = this@EButton.textSize }.measureText(label) + 40).toInt().coerceAtLeast(96) else 96, widthMeasureSpec)
        val h = View.resolveSize(Eink.BAR_H, heightMeasureSpec)
        setMeasuredDimension(w, max(h, minimumHeight))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = RectF(3f, 3f, w - 3f, h - 3f)
        val fg = when { selectedState -> Eink.WHITE; !enabledState -> Eink.GREY; else -> Eink.BLACK }
        if (selectedState) { fill.color = Eink.BLACK; c.drawRoundRect(r, Eink.RADIUS, Eink.RADIUS, fill) }
        else if (bordered) { box.color = if (enabledState) Eink.BLACK else Eink.GREY; c.drawRoundRect(r, Eink.RADIUS, Eink.RADIUS, box) }
        val ic = icon; val lb = label
        text.color = fg; text.textSize = textSize
        iconPaint.color = fg; fill.color = fg
        if (ic != null && lb != null) {
            val s = min(h * 0.44f, w * 0.6f)
            Icons.draw(c, ic, w / 2f, h * 0.38f, s, iconPaint, fill.apply { color = fg })
            text.textSize = min(textSize, 24f)
            c.drawText(fit(lb, w - 8f), w / 2f, h - 14f, text)
        } else if (ic != null) {
            Icons.draw(c, ic, w / 2f, h / 2f, min(h, w) * 0.5f, iconPaint, fill.apply { color = fg })
        } else if (lb != null) {
            c.drawText(fit(lb, w - 16f), w / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text)
        }
    }

    private fun fit(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabledState) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { longFired = false; if (onLongPress != null) handler.postDelayed(longRunnable, 550) }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                if (!longFired && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick(this)
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRunnable)
        }
        return true
    }
}

/** A quick pick ("well"): a width dot or a grey swatch, with a ring when it is the current choice. */
@SuppressLint("ViewConstructor")
class Well(ctx: Context, var kind: Kind, var value: Float, private val onPick: (Well) -> Unit) : View(ctx) {
    enum class Kind { WIDTH, GREY, TEXT }
    var current = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var caption: String? = null
    var onLongPress: ((Well) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var longFired = false
    private val longRunnable = Runnable { longFired = true; onLongPress?.invoke(this) }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        if (current) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Eink.BLACK
            c.drawRoundRect(RectF(4f, 8f, width - 4f, height - 8f), Eink.RADIUS, Eink.RADIUS, p)
        }
        when (kind) {
            Kind.WIDTH -> {
                p.style = Paint.Style.FILL; p.color = Eink.BLACK
                c.drawCircle(cx, cy, (2f + value * 5f).coerceIn(2f, min(width, height) * 0.36f), p)
            }
            Kind.GREY -> {
                val g = value.toInt().coerceIn(0, 255)
                p.style = Paint.Style.FILL; p.color = Color.rgb(g, g, g)
                val r = min(width, height) * 0.26f
                c.drawCircle(cx, cy, r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Eink.BLACK
                c.drawCircle(cx, cy, r, p)
            }
            Kind.TEXT -> {
                p.style = Paint.Style.FILL; p.color = Eink.BLACK; p.textAlign = Paint.Align.CENTER
                p.textSize = (value * 0.6f).coerceIn(18f, min(width, height) * 0.62f)
                c.drawText(caption ?: "A", cx, cy - (p.descent() + p.ascent()) / 2f, p)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { longFired = false; if (onLongPress != null) handler.postDelayed(longRunnable, 550) }
            MotionEvent.ACTION_UP -> { handler.removeCallbacks(longRunnable); if (!longFired) onPick(this) }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRunnable)
        }
        return true
    }
}

/** A thin vertical line between groups in a bar. */
fun Context.divider() = View(this).apply { setBackgroundColor(Eink.LIGHT) }

/** One row of a [Sheet]. */
class Row(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val keepOpen: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Replaces Android's dialogs: a bordered panel at the bottom of the screen with big rows. Long lists are split
 * into pages with ‹ › instead of scrolling (scrolling smears on e-ink). No dimming, no animation.
 * Tapping outside the panel closes it.
 */
class Sheet(private val activity: Activity, private val host: FrameLayout) {
    private var catcher: FrameLayout? = null
    private var onClose: (() -> Unit)? = null
    val isShowing get() = catcher != null

    fun hide() {
        val c = catcher ?: return
        hideKeyboard()
        host.removeView(c)
        catcher = null
        val cb = onClose; onClose = null
        cb?.invoke()
    }

    /** Shows rows, split into pages that fit. Rows close the sheet when tapped unless [Row.keepOpen]. */
    fun list(title: String, rows: List<Row>, startPage: Int = 0, footer: List<EButton> = emptyList(), onClose: (() -> Unit)? = null) {
        val perPage = max(3, ((host.height * 0.72f - 2 * Eink.BAR_H) / Eink.ROW_H).toInt())
        val pages = max(1, (rows.size + perPage - 1) / perPage)
        val page = startPage.coerceIn(0, pages - 1)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows.drop(page * perPage).take(perPage)) body.addView(rowView(r, title, rows, page), LinearLayout.LayoutParams(-1, Eink.ROW_H))
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (pages > 1) {
            bar.addView(EButton(activity, "left", null) { list(title, rows, page - 1, footer, this.onClose) }.apply { enabledState = page > 0 }, LinearLayout.LayoutParams(140, Eink.BAR_H))
            bar.addView(activity.label("${page + 1} / $pages", 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(EButton(activity, "right", null) { list(title, rows, page + 1, footer, this.onClose) }.apply { enabledState = page < pages - 1 }, LinearLayout.LayoutParams(140, Eink.BAR_H))
        }
        for (b in footer) bar.addView(b, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 12 })
        if (bar.childCount > 0) body.addView(bar, LinearLayout.LayoutParams(-1, Eink.BAR_H).apply { topMargin = 12 })
        show(title, body, onClose)
    }

    private fun rowView(r: Row, title: String, rows: List<Row>, page: Int): View {
        val v = object : View(activity) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val fg = if (!r.enabled) Eink.GREY else if (r.selected) Eink.WHITE else Eink.BLACK
                if (r.selected) { p.style = Paint.Style.FILL; p.color = Eink.BLACK; c.drawRoundRect(RectF(4f, 6f, width - 4f, height - 6f), Eink.RADIUS, Eink.RADIUS, p) }
                p.style = Paint.Style.FILL; p.color = Eink.LIGHT
                if (!r.selected) c.drawRect(24f, height - 2f, width - 24f, height.toFloat(), p)
                p.color = fg; p.textSize = 34f; p.typeface = Typeface.DEFAULT
                val y = if (r.detail != null) height * 0.44f else height / 2f - (p.descent() + p.ascent()) / 2f
                c.drawText(r.label, 32f, y, p)
                r.detail?.let { p.textSize = 24f; p.color = if (r.selected) Eink.WHITE else Eink.GREY; c.drawText(it, 32f, height * 0.8f, p) }
            }
        }
        v.isClickable = true
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP && r.enabled) {
                if (!r.keepOpen) hide()
                r.onClick()
            }
            true
        }
        return v
    }

    /** Shows any content view under a title bar with a close button. */
    fun show(title: String, content: View, onClose: (() -> Unit)? = null) {
        val existingClose = this.onClose
        catcher?.let { host.removeView(it) }
        catcher = null
        this.onClose = onClose ?: existingClose
        val c = FrameLayout(activity).apply { isClickable = true; setOnClickListener { hide() } }
        val panel = object : LinearLayout(activity) {
            val p = Paint().apply { color = Eink.BLACK; strokeWidth = Eink.BORDER }
            override fun dispatchDraw(canvas: Canvas) { super.dispatchDraw(canvas); canvas.drawLine(0f, 1.5f, width.toFloat(), 1.5f, p) }
        }.apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Eink.WHITE); isClickable = true
            setPadding(28, 8, 28, 28)
        }
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(activity.label(title, 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(EButton(activity, "close", null) { hide() }.apply { bordered = false }, LinearLayout.LayoutParams(110, Eink.BAR_H))
        panel.addView(head)
        panel.addView(content, LinearLayout.LayoutParams(-1, -2))
        c.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        host.addView(c, FrameLayout.LayoutParams(-1, -1))
        catcher = c
    }

    fun confirm(title: String, message: String, yes: String, no: String = "Cancel", onYes: () -> Unit) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(activity.label(message, 20f).apply { setPadding(4, 8, 4, 24) })
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(EButton(activity, null, no) { hide() }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f))
        bar.addView(EButton(activity, null, yes) { hide(); onYes() }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 24 })
        box.addView(bar)
        show(title, box)
    }

    /** One-choice list. */
    fun <T> choose(title: String, options: List<Pair<String, T>>, current: T?, onPick: (T) -> Unit) {
        list(title, options.map { (name, v) -> Row(name, selected = v == current) { onPick(v) } })
    }

    /** A text field (the on-screen keyboard opens). */
    fun input(title: String, initial: String, hint: String = "", multiline: Boolean = false, password: Boolean = false, ok: String = "OK", onDone: (String) -> Unit) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(activity).apply {
            setText(initial); setSelection(initial.length); this.hint = hint; textSize = 22f; setTextColor(Eink.BLACK)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (multiline) { minLines = 3; maxLines = 8; gravity = Gravity.TOP } else isSingleLine = true
            background = null
            setPadding(16, 16, 16, 16)
        }
        val frame = object : FrameLayout(activity) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Eink.BORDER; color = Eink.BLACK }
            override fun dispatchDraw(canvas: Canvas) { super.dispatchDraw(canvas); canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), Eink.RADIUS, Eink.RADIUS, p) }
        }
        frame.addView(field, FrameLayout.LayoutParams(-1, -2))
        box.addView(frame, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 })
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(EButton(activity, null, "Cancel") { hide() }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f))
        bar.addView(EButton(activity, null, ok) { val t = field.text.toString(); hide(); onDone(t) }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 24 })
        box.addView(bar)
        show(title, box)
        field.requestFocus()
        field.post { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, 0) }
    }

    private fun hideKeyboard() {
        val v = activity.currentFocus ?: return
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }
}

/**
 * Short messages without the fading Toast: a bordered line at the top of the screen that goes away after a while
 * (one appear, one disappear: two small screen updates).
 */
class Notice(private val host: FrameLayout) {
    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val remove = Runnable { view?.let { host.removeView(it) }; view = null }

    fun show(text: String, ms: Long = 3500) {
        handler.removeCallbacks(remove)
        val v = view ?: object : TextView(host.context) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Eink.BORDER; color = Eink.BLACK }
            override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), Eink.RADIUS, Eink.RADIUS, p) }
        }.also {
            it.setBackgroundColor(Eink.WHITE); it.setTextColor(Eink.BLACK); it.textSize = 20f; it.setPadding(32, 24, 32, 24)
            host.addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 230 })
            view = it
        }
        v.text = text
        handler.postDelayed(remove, ms)
    }
}

/**
 * Hand-drawn icons on a 24-unit grid, scaled to [size]. Plain strokes only, so they stay crisp on e-ink.
 */
object Icons {
    fun draw(c: Canvas, name: String, cx: Float, cy: Float, size: Float, stroke: Paint, fill: Paint) {
        val u = size / 24f
        c.save(); c.translate(cx - 12f * u, cy - 12f * u); c.scale(u, u)
        stroke.strokeWidth = 2f; stroke.style = Paint.Style.STROKE; stroke.pathEffect = null
        fill.style = Paint.Style.FILL
        val p = Path()
        when (name) {
            "pen" -> { p.moveTo(4f, 20f); p.lineTo(5f, 15f); p.lineTo(16f, 4f); p.lineTo(20f, 8f); p.lineTo(9f, 19f); p.close(); c.drawPath(p, stroke); c.drawLine(14f, 6f, 18f, 10f, stroke) }
            "calligraphy" -> { p.moveTo(12f, 3f); p.lineTo(18f, 12f); p.lineTo(12f, 21f); p.lineTo(6f, 12f); p.close(); c.drawPath(p, stroke); c.drawLine(12f, 9f, 12f, 21f, stroke); c.drawCircle(12f, 12f, 1.6f, fill) }
            "highlighter" -> { p.moveTo(6f, 14f); p.lineTo(14f, 4f); p.lineTo(20f, 9f); p.lineTo(11f, 18f); p.close(); c.drawPath(p, stroke); c.drawLine(6f, 14f, 4f, 19f, stroke); c.drawLine(4f, 19f, 9f, 19f, stroke); c.drawRect(3f, 21f, 21f, 22.5f, fill) }
            "eraser" -> { p.moveTo(3f, 15f); p.lineTo(12f, 6f); p.lineTo(19f, 13f); p.lineTo(12f, 20f); p.lineTo(8f, 20f); p.close(); c.drawPath(p, stroke); c.drawLine(7.5f, 10.5f, 14.5f, 17.5f, stroke); c.drawLine(12f, 20f, 21f, 20f, stroke) }
            "lasso" -> { stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 2.5f), 0f); c.drawOval(RectF(3f, 4f, 21f, 15f), stroke); stroke.pathEffect = null; p.moveTo(8f, 14f); p.quadTo(5f, 19f, 9f, 21f); c.drawPath(p, stroke) }
            "box" -> { stroke.pathEffect = DashPathEffect(floatArrayOf(3f, 2.5f), 0f); c.drawRect(4f, 5f, 20f, 19f, stroke); stroke.pathEffect = null }
            "shape" -> { c.drawRect(3f, 9f, 13f, 19f, stroke); c.drawCircle(15f, 9f, 6f, stroke) }
            "text" -> { c.drawLine(5f, 5f, 19f, 5f, stroke); c.drawLine(12f, 5f, 12f, 20f, stroke); c.drawLine(9f, 20f, 15f, 20f, stroke) }
            "tape" -> { c.drawRect(3f, 8f, 21f, 16f, stroke); for (x in intArrayOf(6, 10, 14, 18)) c.drawLine(x.toFloat(), 16f, x + 3f, 8f, stroke) }
            "zoomwin" -> { c.drawRect(3f, 3f, 21f, 21f, stroke); c.drawRect(6f, 6f, 12f, 10f, stroke); c.drawLine(3f, 14f, 21f, 14f, stroke); c.drawLine(9f, 10f, 9f, 14f, stroke) }
            "hand" -> { p.moveTo(8f, 21f); p.lineTo(5f, 14f); p.quadTo(4f, 11f, 7f, 12f); p.lineTo(8f, 14f); p.lineTo(8f, 5f); p.quadTo(9.5f, 3f, 11f, 5f); p.lineTo(11f, 11f); p.lineTo(11f, 4f); p.quadTo(12.5f, 2f, 14f, 4f); p.lineTo(14f, 11f); p.lineTo(14f, 5.5f); p.quadTo(15.5f, 3.5f, 17f, 5.5f); p.lineTo(17f, 15f); p.quadTo(17f, 19f, 15f, 21f); c.drawPath(p, stroke) }
            "undo" -> { p.moveTo(9f, 6f); p.lineTo(4f, 11f); p.lineTo(9f, 16f); c.drawPath(p, stroke); val q = Path(); q.moveTo(4f, 11f); q.lineTo(15f, 11f); q.quadTo(20f, 11f, 20f, 16f); q.quadTo(20f, 20f, 15f, 20f); q.lineTo(11f, 20f); c.drawPath(q, stroke) }
            "redo" -> { c.save(); c.scale(-1f, 1f, 12f, 12f); draw(c, "undo", 12f, 12f, 24f, stroke, fill); c.restore() }
            "pages" -> { c.drawRect(6f, 3f, 18f, 18f, stroke); c.drawLine(9f, 21f, 21f, 21f, stroke); c.drawLine(21f, 21f, 21f, 7f, stroke) }
            "mic" -> { c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, stroke); p.moveTo(6f, 11f); p.quadTo(6f, 18f, 12f, 18f); p.quadTo(18f, 18f, 18f, 11f); c.drawPath(p, stroke); c.drawLine(12f, 18f, 12f, 21f, stroke) }
            "stop" -> c.drawRect(6f, 6f, 18f, 18f, fill)
            "play" -> { p.moveTo(7f, 4f); p.lineTo(19f, 12f); p.lineTo(7f, 20f); p.close(); c.drawPath(p, fill) }
            "pause" -> { c.drawRect(6f, 5f, 10f, 19f, fill); c.drawRect(14f, 5f, 18f, 19f, fill) }
            "more" -> { c.drawCircle(5f, 12f, 2f, fill); c.drawCircle(12f, 12f, 2f, fill); c.drawCircle(19f, 12f, 2f, fill) }
            "back", "left" -> { p.moveTo(15f, 4f); p.lineTo(7f, 12f); p.lineTo(15f, 20f); c.drawPath(p, stroke) }
            "right" -> { p.moveTo(9f, 4f); p.lineTo(17f, 12f); p.lineTo(9f, 20f); c.drawPath(p, stroke) }
            "up" -> { p.moveTo(4f, 15f); p.lineTo(12f, 7f); p.lineTo(20f, 15f); c.drawPath(p, stroke) }
            "down" -> { p.moveTo(4f, 9f); p.lineTo(12f, 17f); p.lineTo(20f, 9f); c.drawPath(p, stroke) }
            "close" -> { c.drawLine(5f, 5f, 19f, 19f, stroke); c.drawLine(19f, 5f, 5f, 19f, stroke) }
            "plus" -> { c.drawLine(12f, 4f, 12f, 20f, stroke); c.drawLine(4f, 12f, 20f, 12f, stroke) }
            "check" -> { p.moveTo(4f, 13f); p.lineTo(9f, 18f); p.lineTo(20f, 6f); c.drawPath(p, stroke) }
            "gear" -> { c.drawCircle(12f, 12f, 3.5f, stroke); for (i in 0 until 8) { val a = Math.PI * i / 4; c.drawLine(12f + 6f * Math.cos(a).toFloat(), 12f + 6f * Math.sin(a).toFloat(), 12f + 9f * Math.cos(a).toFloat(), 12f + 9f * Math.sin(a).toFloat(), stroke) }; c.drawCircle(12f, 12f, 6f, stroke) }
            "search" -> { c.drawCircle(10f, 10f, 6f, stroke); c.drawLine(14.5f, 14.5f, 20f, 20f, stroke) }
            "ai" -> { p.moveTo(12f, 3f); p.quadTo(13f, 11f, 21f, 12f); p.quadTo(13f, 13f, 12f, 21f); p.quadTo(11f, 13f, 3f, 12f); p.quadTo(11f, 11f, 12f, 3f); c.drawPath(p, stroke); c.drawCircle(19f, 5f, 1.5f, fill) }
            "star" -> { for (i in 0 until 5) { val a = -Math.PI / 2 + i * 2 * Math.PI / 5; val b = a + Math.PI / 5; val x1 = 12f + 9f * Math.cos(a).toFloat(); val y1 = 12.5f + 9f * Math.sin(a).toFloat(); val x2 = 12f + 4f * Math.cos(b).toFloat(); val y2 = 12.5f + 4f * Math.sin(b).toFloat(); if (i == 0) p.moveTo(x1, y1) else p.lineTo(x1, y1); p.lineTo(x2, y2) }; p.close(); c.drawPath(p, stroke) }
            "starfill" -> { draw(c, "star", 12f, 12f, 24f, fill, fill) }
            "folder" -> { p.moveTo(3f, 6f); p.lineTo(9f, 6f); p.lineTo(11f, 8f); p.lineTo(21f, 8f); p.lineTo(21f, 19f); p.lineTo(3f, 19f); p.close(); c.drawPath(p, stroke) }
            "trash" -> { c.drawLine(4f, 6f, 20f, 6f, stroke); c.drawRect(6f, 6f, 18f, 21f, stroke); c.drawLine(9f, 3f, 15f, 3f, stroke); c.drawLine(10f, 10f, 10f, 17f, stroke); c.drawLine(14f, 10f, 14f, 17f, stroke) }
            "export" -> { c.drawLine(12f, 3f, 12f, 15f, stroke); p.moveTo(7f, 8f); p.lineTo(12f, 3f); p.lineTo(17f, 8f); c.drawPath(p, stroke); val q = Path(); q.moveTo(5f, 12f); q.lineTo(5f, 21f); q.lineTo(19f, 21f); q.lineTo(19f, 12f); c.drawPath(q, stroke) }
            "ruler" -> { c.save(); c.rotate(-35f, 12f, 12f); c.drawRect(1f, 9f, 23f, 15f, stroke); for (x in 4..20 step 3) c.drawLine(x.toFloat(), 9f, x.toFloat(), if (x % 2 == 0) 12.5f else 11f, stroke); c.restore() }
            "bookmark" -> { p.moveTo(6f, 3f); p.lineTo(18f, 3f); p.lineTo(18f, 21f); p.lineTo(12f, 16f); p.lineTo(6f, 21f); p.close(); c.drawPath(p, stroke) }
            "refresh" -> { c.drawArc(RectF(4f, 4f, 20f, 20f), -60f, 300f, false, stroke); p.moveTo(18f, 2f); p.lineTo(18.5f, 7.5f); p.lineTo(13f, 7.5f); c.drawPath(p, stroke) }
            "image" -> { c.drawRect(3f, 5f, 21f, 19f, stroke); p.moveTo(3f, 17f); p.lineTo(9f, 11f); p.lineTo(14f, 16f); p.lineTo(17f, 13f); p.lineTo(21f, 17f); c.drawPath(p, stroke); c.drawCircle(16f, 9f, 1.6f, fill) }
            "pdf" -> { p.moveTo(5f, 3f); p.lineTo(15f, 3f); p.lineTo(19f, 7f); p.lineTo(19f, 21f); p.lineTo(5f, 21f); p.close(); c.drawPath(p, stroke); c.drawLine(8f, 12f, 16f, 12f, stroke); c.drawLine(8f, 16f, 16f, 16f, stroke) }
            "book" -> { p.moveTo(12f, 6f); p.quadTo(7f, 3f, 3f, 5f); p.lineTo(3f, 19f); p.quadTo(7f, 17f, 12f, 20f); p.quadTo(17f, 17f, 21f, 19f); p.lineTo(21f, 5f); p.quadTo(17f, 3f, 12f, 6f); p.lineTo(12f, 20f); c.drawPath(p, stroke) }
            else -> c.drawCircle(12f, 12f, 6f, stroke)
        }
        stroke.pathEffect = null
        c.restore()
    }
}

/** Stretch-in-a-row layout params. */
fun weightParams(w: Float = 1f, h: Int = ViewGroup.LayoutParams.MATCH_PARENT) = LinearLayout.LayoutParams(0, h, w)
