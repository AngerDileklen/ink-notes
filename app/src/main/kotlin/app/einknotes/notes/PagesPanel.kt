package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Icons
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label
import java.util.concurrent.Executors

/**
 * Thumbnails of the pages, six at a time (no scrolling: it smears on e-ink). Tap a page to pick it (tap it again to
 * open it), then open, copy, delete, move, add a page after it or bookmark it.
 * Thumbnails are drawn one at a time on a worker thread and kept in a small cache.
 */
class PagesPanel(
    context: Context,
    private val note: NoteData,
    private val pdfs: PdfLibrary,
    private val ink: InkView,
    private val sheet: Sheet,
    private val onClose: () -> Unit,
    private val onOpen: (Int) -> Unit
) : LinearLayout(context) {

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = LruCache<PageData, Bitmap>(14)
    private val requested = HashSet<PageData>()
    private var selected = 0
    private var gridPage = 0
    private val COLS = 3
    private val ROWS = 2
    private val title: TextView = context.label("Pages", 26f, bold = true)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val pageLabel: TextView = context.label("", 22f).apply { gravity = Gravity.CENTER }
    private lateinit var prevBtn: EButton
    private lateinit var nextBtn: EButton

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        isClickable = true
        setPadding(14, 4, 14, 8)
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 0, 0, 0) }
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(EButton(context, "close", "Close") { onClose() }.apply { bordered = false }, LayoutParams(150, Eink.BAR_H))
        addView(head)
        addView(View(context).apply { setBackgroundColor(Color.BLACK) }, LayoutParams(LayoutParams.MATCH_PARENT, 3))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val pager = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prevBtn = EButton(context, "left", null) { gridPage--; refresh() }
        nextBtn = EButton(context, "right", null) { gridPage++; refresh() }
        pager.addView(prevBtn, LayoutParams(150, Eink.BAR_H))
        pager.addView(pageLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pager.addView(nextBtn, LayoutParams(150, Eink.BAR_H))
        addView(pager)

        fun bar(vararg b: Pair<String, () -> Unit>) = addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            for ((t, f) in b) addView(EButton(context, null, t) { f() }.apply { textSize = 26f }, LayoutParams(0, Eink.BAR_H, 1f).apply { setMargins(4, 4, 4, 4) })
        })
        bar("Open page" to { onOpen(selected) }, "Copy page" to { copy() }, "Delete" to { delete() })
        bar("Move earlier" to { move(-1) }, "Move later" to { move(1) }, "Blank page after" to { blankAfter() })
        bar("Bookmark on/off" to { toggleBookmark() }, "Next bookmark" to { nextBookmark() }, "Go to number" to { goToNumber() })
    }

    /** Shows the panel for the page being viewed. */
    fun show() {
        selected = ink.pageIndex.coerceIn(0, note.pages.size - 1)
        gridPage = selected / (COLS * ROWS)
        clearCache()
        refresh()
    }

    private fun refresh() {
        val per = COLS * ROWS
        val pages = maxOf(1, (note.pages.size + per - 1) / per)
        gridPage = gridPage.coerceIn(0, pages - 1)
        title.text = "Pages (${note.pages.size})"
        pageLabel.text = "${gridPage + 1} / $pages"
        prevBtn.enabledState = gridPage > 0
        nextBtn.enabledState = gridPage < pages - 1
        grid.removeAllViews()
        for (r in 0 until ROWS) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (c in 0 until COLS) {
                val pos = gridPage * per + r * COLS + c
                val v: View = if (pos < note.pages.size) Thumb(context, pos) else View(context)
                row.addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(8, 8, 8, 8) })
            }
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun clearCache() { cache.evictAll(); requested.clear() }

    private inner class Thumb(ctx: Context, val position: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val page = note.pages[position]

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val sel = position == selected
            p.style = Paint.Style.STROKE; p.strokeWidth = if (sel) 8f else 3f; p.color = Color.BLACK
            c.drawRoundRect(RectF(4f, 4f, w - 4f, h - 4f), Eink.RADIUS, Eink.RADIUS, p)
            val box = RectF(18f, 18f, w - 18f, h - 70f)
            val b = cache.get(page)
            p.strokeWidth = 2f; p.color = Eink.GREY
            if (b != null) {
                val s = minOf(box.width() / b.width, box.height() / b.height)
                val dw = b.width * s; val dh = b.height * s
                val dst = RectF(box.centerX() - dw / 2, box.top, box.centerX() + dw / 2, box.top + dh)
                c.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawRect(dst, p)
            } else {
                load(page)
                val dw = box.height() * (page.w / page.h)
                c.drawRect(box.centerX() - dw / 2, box.top, box.centerX() + dw / 2, box.bottom, p)
            }
            p.style = Paint.Style.FILL; p.color = Color.BLACK; p.textSize = 28f
            p.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val txt = "${position + 1}" + if (position == ink.pageIndex) "  (current)" else ""
            c.drawText(txt, 24f, h - 26f, p)
            if (page.bookmarked) Icons.draw(c, "starfill", w - 46f, h - 40f, 36f, stroke, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (selected == position) onOpen(position) else { selected = position; refresh() }
            }
            return true
        }
    }

    private fun load(page: PageData) {
        if (!requested.add(page)) return
        exec.execute {
            val bmp = try { Thumbnailer.render(page, pdfs, 300) } catch (_: Throwable) { null }
            ui.post { if (bmp != null) { cache.put(page, bmp); refresh() } }
        }
    }

    private fun copy() {
        ink.duplicatePage(selected); clearCache()
        selected = (selected + 1).coerceAtMost(note.pages.size - 1); refresh()
    }

    private fun blankAfter() { ink.insertBlankAfter(selected); clearCache(); selected += 1; gridPage = selected / (COLS * ROWS); refresh() }

    private fun delete() {
        val page = note.pages.getOrNull(selected) ?: return
        val what = if (page.items.isEmpty()) "page ${selected + 1}" else "page ${selected + 1} and everything written on it"
        sheet.confirm("Delete page ${selected + 1}?", "Delete $what?", "Delete") {
            ink.deletePageAt(selected); clearCache()
            selected = selected.coerceIn(0, note.pages.size - 1); refresh()
        }
    }

    private fun toggleBookmark() {
        val p = note.pages.getOrNull(selected) ?: return
        p.bookmarked = !p.bookmarked
        ink.markChanged()
        refresh()
    }

    /** Selects the next bookmarked page after the selected one, wrapping around. */
    private fun nextBookmark() {
        val n = note.pages.size
        for (k in 1..n) {
            val i = (selected + k) % n
            if (note.pages[i].bookmarked) { selected = i; gridPage = i / (COLS * ROWS); refresh(); return }
        }
    }

    private fun goToNumber() {
        sheet.input("Go to page", "", hint = "1 - ${note.pages.size}", ok = "Go") { t ->
            val n = t.trim().toIntOrNull()
            if (n != null && n in 1..note.pages.size) onOpen(n - 1)
        }
    }

    private fun move(delta: Int) {
        val to = selected + delta
        if (to !in note.pages.indices) return
        ink.movePage(selected, to); selected = to; gridPage = to / (COLS * ROWS); refresh()
    }
}
