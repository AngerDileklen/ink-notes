package app.einknotes.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import app.einknotes.notes.PAGE_W
import app.einknotes.notes.PdfLibrary
import app.einknotes.notes.PdfTextEngine
import app.einknotes.notes.PageText
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A PDF shown as a book: the page fills the width of the screen and "next" moves down a screen at a time (then on to the next
 * page). Words are looked up from the PDF's own text layer, so a tap on a word gives the dictionary and a drag highlights lines.
 */
@SuppressLint("ViewConstructor")
class PdfBookView(
    context: Context,
    private val book: Book,
    private val pdfs: PdfLibrary,
    private val text: PdfTextEngine,
    private val host: Host
) : View(context), ReaderBackend {

    interface Host {
        fun onWord(word: String, sentence: String, start: Int, end: Int, highlightId: String)
        fun onSelection(text: String, start: Int, end: Int)
        fun onBlankTap()
        fun onPosition()
        fun highlightsOnPage(page: Int): List<Highlight>
    }

    private val file = book.file!!
    var page = 0; private set
    /** 1 = page fills the width; smaller shows the whole page. */
    var zoom = 1f
    private var ty = 0f
    private var tx = 0f
    private var pageH = PAGE_W * 1.414f
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var bitmap: Bitmap? = null
    private var token = 0
    private var selection: List<RectF> = emptyList()
    private var pageText: PageText? = null
    private val hlPaint = Paint().apply { color = Color.rgb(190, 190, 190); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val selPaint = Paint().apply { color = Color.rgb(150, 150, 150); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val hitPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 5f }
    private var hits: List<RectF> = emptyList()

    private val scale get() = (width / PAGE_W) * zoom

    override val view: View get() = this

    fun open(startPage: Int, scrollFraction: Float) {
        page = startPage.coerceIn(0, maxOf(0, book.pageCount - 1))
        loadPage(scrollFraction)
    }

    private fun loadPage(scrollFraction: Float = 0f, atBottom: Boolean = false) {
        val size = try { pdfs.pageSize(file, page) } catch (_: Throwable) { 595 to 842 }
        pageH = PAGE_W * size.second / max(1, size.first).toFloat()
        selection = emptyList(); hits = emptyList(); pageText = null
        val h = pageH * scale
        val range = max(0f, h - height)
        ty = if (atBottom) -range else -(range * scrollFraction)
        tx = max(0f, (width - PAGE_W * scale) / 2f)
        render()
        val loading = page
        exec.execute { val pt = text.pageText(file, loading); ui.post { if (page == loading) { pageText = pt; invalidate() } } }
        host.onPosition()
    }

    private fun render() {
        if (width <= 0 || height <= 0) return
        val my = ++token
        val w = width; val h = height; val sc = scale; val x = tx; val y = ty; val idx = page
        exec.execute {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
            val ok = bmp != null && pdfs.render(file, idx, bmp, PAGE_W, sc, x, y)
            ui.post {
                if (my == token && ok && bmp != null) { val old = bitmap; bitmap = bmp; if (old !== bmp) old?.recycle(); invalidate() }
                else bmp?.recycle()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (w > 0 && h > 0) loadPage(scrollFraction()) }

    private fun scrollFraction(): Float { val range = max(0f, pageH * scale - height); return if (range <= 0f) 0f else (-ty / range).coerceIn(0f, 1f) }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        bitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
        val sc = scale
        fun draw(r: RectF, p: Paint, pad: Float = 0f) = c.drawRect(tx + r.left * sc - pad, ty + r.top * sc - pad, tx + r.right * sc + pad, ty + r.bottom * sc + pad, p)
        val pt = pageText
        if (pt != null) for (h in host.highlightsOnPage(page)) for (r in pt.lineRects(h.start, h.end - 1)) draw(r, hlPaint)
        for (r in selection) draw(r, selPaint)
        for (r in hits) draw(r, hitPaint, 4f)
    }

    // ---- turning ---------------------------------------------------------------------------------------------------------------

    override fun next() {
        val bottom = -(max(0f, pageH * scale - height))
        if (ty - 4f > bottom + height * 0.1f && pageH * scale > height) { ty = max(bottom, ty - height * 0.9f); clearOverlays(); render(); host.onPosition() }
        else if (page + 1 < book.pageCount) { page++; loadPage() }
    }

    override fun prev() {
        if (ty < -4f) { ty = min(0f, ty + height * 0.9f); clearOverlays(); render(); host.onPosition() }
        else if (page > 0) { page--; loadPage(atBottom = true) }
    }

    fun goToPage(p: Int) { page = p.coerceIn(0, maxOf(0, book.pageCount - 1)); loadPage() }

    /** The zoom at which the whole page is on the screen. */
    fun wholePageZoom(): Float { val full = pageH * (width / PAGE_W); return if (full <= 0f) 1f else min(1f, height / full) }

    fun applyZoom() { loadPage(scrollFraction()) }

    private fun clearOverlays() { selection = emptyList(); hits = emptyList(); invalidate() }

    fun showHits(rects: List<RectF>) { hits = rects; invalidate() }

    fun clearSelection() { selection = emptyList(); invalidate() }

    override fun label() = "Page ${page + 1} of ${book.pageCount}"
    override fun percent() = if (book.pageCount > 1) ((page + (if (pageH * scale > height) scrollFraction() else 0f)) / book.pageCount).coerceIn(0f, 1f) else 0f
    override fun chapterTitle() = ""
    override fun relayout() = applyZoom()
    override fun savePosition(state: ReadingState) { state.page = page; state.scroll = scrollFraction() }
    override fun destroy() { token++; exec.shutdownNow(); bitmap?.recycle(); bitmap = null }

    /** Text of this page and the next, for the AI. */
    fun pageTextForAi(): String {
        val sb = StringBuilder()
        for (p in page..min(page + 1, book.pageCount - 1)) text.pageText(file, p)?.text?.let { sb.append(it).append("\n\n") }
        return sb.toString().take(14000)
    }

    // ---- touch -----------------------------------------------------------------------------------------------------------------------

    private var dx0 = 0f; private var dy0 = 0f; private var t0 = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx0 = e.x; dy0 = e.y; t0 = System.currentTimeMillis() }
            MotionEvent.ACTION_UP -> {
                val dist = hypot(e.x - dx0, e.y - dy0)
                if (dist < 16f && System.currentTimeMillis() - t0 < 700) tap(e.x, e.y)
                else if (dist >= 16f) {
                    if (Math.abs(e.x - dx0) > width * 0.5f && Math.abs(e.y - dy0) < 90f) { if (e.x < dx0) next() else prev() }
                    else drag(dx0, dy0, e.x, e.y)
                }
            }
        }
        return true
    }

    private fun toPage(x: Float, y: Float) = ((x - tx) / scale) to ((y - ty) / scale)

    private fun wordRange(t: String, i: Int): IntRange {
        fun ok(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
        var a = i; var b = i
        while (a > 0 && ok(t[a - 1])) a--
        while (b < t.length - 1 && ok(t[b + 1])) b++
        while (a < b && !t[a].isLetterOrDigit()) a++
        while (b > a && !t[b].isLetterOrDigit()) b--
        return a..b
    }

    private fun tap(x: Float, y: Float) {
        if (x < width * 0.12f) { prev(); return }
        if (x > width * 0.88f) { next(); return }
        val pt = pageText
        if (pt == null) { host.onBlankTap(); return }
        val (px, py) = toPage(x, y)
        val i = pt.charAt(px, py, 14f)
        if (i < 0 || !pt.text[i].isLetterOrDigit()) { clearSelection(); host.onBlankTap(); return }
        val r = wordRange(pt.text, i)
        selection = pt.lineRects(r.first, r.last); invalidate()
        val sentence = sentenceAround(pt.text, r.first, r.last)
        val hl = host.highlightsOnPage(page).firstOrNull { r.first >= it.start && r.last < it.end }
        host.onWord(pt.text.substring(r.first, r.last + 1), sentence, r.first, r.last + 1, hl?.time?.toString() ?: "")
    }

    private fun sentenceAround(t: String, a: Int, b: Int): String {
        var s = a; while (s > 0 && t[s - 1] !in ".!?\n") s--
        var e = b; while (e < t.length - 1 && t[e] !in ".!?\n") e++
        return t.substring(s, min(t.length, e + 1)).replace(Regex("\\s+"), " ").trim().take(300)
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        val pt = pageText ?: return
        val (ax, ay) = toPage(x0, y0); val (bx, by) = toPage(x1, y1)
        val a = pt.charAt(ax, ay, 30f); val b = pt.charAt(bx, by, 30f)
        if (a < 0 || b < 0) return
        val s = min(a, b); val e = max(a, b)
        val ra = wordRange(pt.text, s); val rb = wordRange(pt.text, e)
        selection = pt.lineRects(ra.first, rb.last); invalidate()
        host.onSelection(pt.text.substring(ra.first, rb.last + 1).replace(Regex("\\s+"), " ").trim(), ra.first, rb.last + 1)
    }
}
