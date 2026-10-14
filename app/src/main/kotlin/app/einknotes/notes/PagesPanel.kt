package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A grid of page thumbnails. Tap a page to pick it, then open, copy, delete, move or add after it.
 * Thumbnails are drawn on a worker thread one at a time, as they scroll into view.
 */
class PagesPanel(
    context: Context,
    private val note: NoteData,
    private val pdfs: PdfLibrary,
    private val ink: InkView,
    private val onClose: () -> Unit,
    private val onOpen: (Int) -> Unit
) : LinearLayout(context) {

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = HashMap<PageData, Bitmap>()
    private val requested = HashSet<PageData>()
    private var selected = 0
    private val grid = GridView(context)
    private val title = TextView(context)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = note.pages.size
        override fun getItem(p: Int) = note.pages[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 10, 10, 10)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) },
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420))
                addView(TextView(context).apply { textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.BLACK) })
            }
            val page = note.pages[position]
            val img = cell.getChildAt(0) as ImageView
            val label = cell.getChildAt(1) as TextView
            img.setImageBitmap(cache[page])
            if (cache[page] == null) load(page)
            label.text = (if (page.bookmarked) "★ " else "") + "${position + 1}" + if (position == ink.pageIndex) "  (current)" else ""
            label.typeface = if (position == selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cell.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (position == selected) 8 else 2, Color.BLACK)
                cornerRadius = 6f
            }
            cell.setOnClickListener { selected = position; notifyDataSetChanged() }
            return cell
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        isClickable = true
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 16, 24, 4) }
        title.textSize = 26f; title.typeface = Typeface.DEFAULT_BOLD; title.setTextColor(Color.BLACK)
        head.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(button("Close") { onClose() }, LayoutParams(260, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(head)
        grid.numColumns = 3
        grid.adapter = adapter
        grid.setBackgroundColor(Color.WHITE)
        grid.selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar1 = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar1.addView(button("Open page") { onOpen(selected) }, weight())
        bar1.addView(button("Copy") { ink.duplicatePage(selected); clearCache(); selected = (selected + 1).coerceAtMost(note.pages.size - 1); refresh() }, weight())
        bar1.addView(button("Delete") { delete() }, weight())
        addView(bar1)
        val bar2 = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar2.addView(button("Move earlier") { move(-1) }, weight())
        bar2.addView(button("Move later") { move(1) }, weight())
        bar2.addView(button("Blank page after") { ink.insertBlankAfter(selected); clearCache(); selected += 1; refresh() }, weight())
        addView(bar2)
        val bar3 = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar3.addView(button("Bookmark on/off") { toggleBookmark() }, weight())
        bar3.addView(button("Next bookmark") { nextBookmark() }, weight())
        bar3.addView(button("Go to number...") { goToNumber() }, weight())
        addView(bar3)
    }

    /** Shows the panel for the page being viewed. */
    fun show() {
        selected = ink.pageIndex.coerceIn(0, note.pages.size - 1)
        clearCache()
        refresh()
        grid.post { grid.setSelection(selected) }
    }

    private fun refresh() {
        title.text = "Pages (${note.pages.size})"
        adapter.notifyDataSetChanged()
    }

    private fun clearCache() {
        cache.clear(); requested.clear()   // not recycled: a cell may still be showing one until it is redrawn
    }

    private fun delete() {
        val page = note.pages.getOrNull(selected) ?: return
        val what = if (page.items.isEmpty()) "this page" else "page ${selected + 1} and everything written on it"
        android.app.AlertDialog.Builder(context)
            .setMessage("Delete $what?")
            .setPositiveButton("Delete") { _, _ ->
                ink.deletePageAt(selected); clearCache()
                selected = selected.coerceIn(0, note.pages.size - 1); refresh()
            }
            .setNegativeButton("Keep", null).show()
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
            if (note.pages[i].bookmarked) { selected = i; refresh(); grid.setSelection(i); return }
        }
    }

    private fun goToNumber() {
        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 - ${note.pages.size}"
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Go to page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..note.pages.size) onOpen(n - 1)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun move(delta: Int) {
        val to = selected + delta
        if (to !in note.pages.indices) return
        ink.movePage(selected, to); selected = to; refresh()
    }

    private fun load(page: PageData) {
        if (!requested.add(page)) return
        exec.execute {
            val bmp = try { Thumbnailer.render(page, pdfs, 300) } catch (_: Throwable) { null }
            ui.post { if (bmp != null) { cache[page] = bmp; adapter.notifyDataSetChanged() } }
        }
    }

    private fun weight() = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label; textSize = 15f; isAllCaps = false; minHeight = 100; minimumHeight = 100
        stateListAnimator = null; setTextColor(Color.BLACK); setPadding(4, 0, 4, 0)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK); cornerRadius = 8f }
        setOnClickListener { onClick() }
    }
}
