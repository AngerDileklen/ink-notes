package app.einknotes.reader

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.LibraryActivity
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Notice
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label
import java.io.File
import kotlin.concurrent.thread

/**
 * The reading library: every book added to the app (EPUB, PDF, FB2, Kindle, text, HTML, comics) as a card with its cover
 * and how far you have read. Books are added from the file picker or by searching the tablet for ebook files.
 */
class BooksActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var sheet: Sheet
    private lateinit var notice: Notice
    private lateinit var sectionBar: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var prevBtn: EButton
    private lateinit var nextBtn: EButton
    private lateinit var sortBtn: EButton
    private lateinit var busyPanel: LinearLayout
    private lateinit var busyText: TextView
    private val prefs get() = getSharedPreferences("books", MODE_PRIVATE)
    private var busy = false
    private var section = "all"
    private var gridPage = 0
    private val COLS = 3
    private val ROWS = 2
    private val REQ_PICK = 61
    private val REQ_STORAGE = 62
    private var pendingScan = false
    private var debugReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = prefs.getString("section", "all") ?: "all"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(16, 8, 16, 8) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Books", 30f, bold = true).apply { setPadding(12, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(EButton(this, "plus", "Add books") { pickBooks() }.apply { bordered = false }, LinearLayout.LayoutParams(190, Eink.BAR_H + 8))
        head.addView(EButton(this, "search", "Find on tablet") { findOnTablet() }.apply { bordered = false }, LinearLayout.LayoutParams(220, Eink.BAR_H + 8))
        root.addView(head)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(EButton(this, null, "Notebooks") { goNotebooks() }.apply { textSize = 28f }, LinearLayout.LayoutParams(0, Eink.BAR_H - 10, 1f).apply { setMargins(0, 4, 6, 4) })
        tabs.addView(EButton(this, null, "Books") { }.apply { textSize = 28f; selectedState = true }, LinearLayout.LayoutParams(0, Eink.BAR_H - 10, 1f).apply { setMargins(6, 4, 0, 4) })
        root.addView(tabs)

        sectionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(sectionBar, LinearLayout.LayoutParams(-1, Eink.BAR_H).apply { topMargin = 4 })
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 3).apply { topMargin = 4 })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 0) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prevBtn = EButton(this, "left", null) { gridPage--; refresh() }
        nextBtn = EButton(this, "right", null) { gridPage++; refresh() }
        pageLabel = label("", 22f).apply { gravity = Gravity.CENTER }
        sortBtn = EButton(this, null, "Sort") { chooseSort() }.apply { textSize = 24f }
        pager.addView(sortBtn, LinearLayout.LayoutParams(300, Eink.BAR_H))
        pager.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pager.addView(prevBtn, LinearLayout.LayoutParams(150, Eink.BAR_H))
        pager.addView(pageLabel, LinearLayout.LayoutParams(200, -2))
        pager.addView(nextBtn, LinearLayout.LayoutParams(150, Eink.BAR_H))
        root.addView(pager)

        frame = FrameLayout(this)
        frame.addView(root)
        busyPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true; setPadding(60, 400, 60, 60); visibility = View.GONE }
        busyText = label("", 22f)
        busyPanel.addView(busyText)
        frame.addView(busyPanel)
        setContentView(frame)
        sheet = Sheet(this, frame)
        notice = Notice(frame)
        registerDebug()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent) }

    override fun onResume() { super.onResume(); refresh() }

    @Deprecated("Back closes an open sheet first")
    override fun onBackPressed() { if (sheet.isShowing) sheet.hide() else goNotebooks() }

    private fun goNotebooks() {
        startActivity(Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        overridePendingTransition(0, 0)
        finish()
    }

    // ---- list ---------------------------------------------------------------------------------------------------------------

    private class Entry(val book: Book, val percent: Float, val lastRead: Long)

    private fun percentOf(b: Book, s: ReadingState): Float = when (b.format) {
        "pdf", "cbz" -> if (b.pageCount > 1) (s.page / (b.pageCount - 1f)).coerceIn(0f, 1f) else 0f
        else -> {
            val sizes = b.spineSizes; val total = sizes.sum().toFloat()
            if (sizes.size == b.spine.size && total > 0 && s.chapter in sizes.indices) ((sizes.take(s.chapter).sum() + sizes[s.chapter] * s.fraction) / total).coerceIn(0f, 1f)
            else if (b.spine.isNotEmpty()) ((s.chapter + s.fraction) / b.spine.size).coerceIn(0f, 1f) else 0f
        }
    }

    private fun entries(): List<Entry> = BookStore.list(this).map { b ->
        val st = File(BookStore.dir(this, b.id), "state.json")
        Entry(b, percentOf(b, BookStore.loadState(this, b.id)), if (st.exists()) st.lastModified() else 0L)
    }

    private fun shown(): List<Entry> {
        val all = entries()
        val f = when (section) {
            "reading" -> all.filter { it.percent > 0.005f && it.percent < 0.98f }
            "finished" -> all.filter { it.percent >= 0.98f }
            "new" -> all.filter { it.percent <= 0.005f }
            else -> all
        }
        return when (prefs.getString("sort", "read")) {
            "title" -> f.sortedBy { it.book.title.lowercase() }
            "author" -> f.sortedBy { it.book.author.lowercase().ifEmpty { "~" } }
            "added" -> f.sortedByDescending { it.book.addedAt }
            else -> f.sortedWith(compareByDescending<Entry> { it.lastRead }.thenByDescending { it.book.addedAt })
        }
    }

    private fun refresh() {
        if (!::grid.isInitialized) return
        sectionBar.removeAllViews()
        for ((key, name) in listOf("all" to "All", "reading" to "Reading", "finished" to "Finished", "new" to "Not started")) {
            sectionBar.addView(EButton(this, null, name) { section = key; gridPage = 0; prefs.edit().putString("section", key).apply(); refresh() }
                .apply { textSize = 26f; selectedState = section == key; bordered = false }, LinearLayout.LayoutParams(-2, Eink.BAR_H).apply { rightMargin = 6 })
        }
        sortBtn.label = "Sort: " + when (prefs.getString("sort", "read")) { "title" -> "title"; "author" -> "author"; "added" -> "date added"; else -> "last read" }
        val list = shown()
        val per = COLS * ROWS
        val pages = maxOf(1, (list.size + per - 1) / per)
        gridPage = gridPage.coerceIn(0, pages - 1)
        pageLabel.text = "${gridPage + 1} / $pages"
        prevBtn.enabledState = gridPage > 0
        nextBtn.enabledState = gridPage < pages - 1
        grid.removeAllViews()
        if (list.isEmpty()) {
            grid.addView(label(if (entries().isEmpty()) "No books yet.\n\nTap Add books to pick files (EPUB, PDF, FB2, Kindle, text), or Find on tablet to list every ebook file stored on this tablet."
            else "No books in this list.", 20f).apply { setPadding(16, 40, 16, 16) })
            return
        }
        val items = list.drop(gridPage * per).take(per)
        for (r in 0 until ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until COLS) {
                val e = items.getOrNull(r * COLS + c)
                row.addView(if (e != null) Card(this, e) else View(this), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(10, 10, 10, 10) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun chooseSort() {
        sheet.choose("Sort books", listOf("Last read" to "read", "Title" to "title", "Author" to "author", "Date added" to "added"), prefs.getString("sort", "read")) {
            prefs.edit().putString("sort", it).apply(); refresh()
        }
    }

    private fun cover(b: Book): Bitmap? {
        val rel = b.cover ?: return null
        val f = File(BookStore.content(this, b.id), rel)
        if (!f.exists()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1; while (o.outWidth / (s * 2) >= 360) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }

    private inner class Card(ctx: Context, val e: Entry) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val cover = cover(e.book)
        private var downAt = 0L

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val textH = 150f
            p.style = Paint.Style.STROKE; p.strokeWidth = Eink.BORDER; p.color = Color.BLACK
            c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), Eink.RADIUS, Eink.RADIUS, p)
            val box = RectF(30f, 20f, w - 30f, h - textH - 6f)
            val b = cover
            if (b != null) {
                val s = minOf(box.width() / b.width, box.height() / b.height)
                val dw = b.width * s; val dh = b.height * s
                val dst = RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2)
                c.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Eink.GREY; c.drawRect(dst, p)
            } else {
                // no cover picture: a plain cover with the title
                val cw = box.height() * 0.68f
                val r = RectF(box.centerX() - cw / 2, box.top, box.centerX() + cw / 2, box.bottom)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Eink.GREY; c.drawRect(r, p)
                tp.color = Color.BLACK; tp.textSize = 26f; tp.typeface = Typeface.DEFAULT_BOLD
                val sl = StaticLayout.Builder.obtain(e.book.title, 0, e.book.title.length, tp, (r.width() - 24).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(6).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
                c.save(); c.translate(r.left + 12, r.top + 30); sl.draw(c); c.restore()
            }
            // text
            p.style = Paint.Style.FILL; p.color = Color.BLACK; p.textSize = 28f; p.typeface = Typeface.DEFAULT_BOLD
            var title = e.book.title
            val maxW = w - 110f
            while (title.length > 1 && p.measureText(title) > maxW) title = title.dropLast(2) + "…"
            c.drawText(title, 24f, h - textH + 40f, p)
            p.typeface = Typeface.DEFAULT; p.textSize = 22f; p.color = Eink.GREY
            var author = e.book.author.ifEmpty { e.book.format.uppercase() }
            while (author.length > 1 && p.measureText(author) > maxW) author = author.dropLast(2) + "…"
            c.drawText(author, 24f, h - textH + 74f, p)
            val pct = (e.percent * 100).toInt()
            c.drawText(e.book.format.uppercase() + " · " + (if (pct >= 98) "finished" else if (pct <= 0) "not started" else "$pct%"), 24f, h - textH + 104f, p)
            // progress line
            p.color = Eink.LIGHT; c.drawRect(24f, h - 30f, w - 24f, h - 22f, p)
            p.color = Color.BLACK; c.drawRect(24f, h - 30f, 24f + (w - 48f) * e.percent, h - 22f, p)
            // menu dots
            Icons.dots(c, w - 54f, h - textH + 30f)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> downAt = SystemClock.uptimeMillis()
                MotionEvent.ACTION_UP -> {
                    val long = SystemClock.uptimeMillis() - downAt > 550
                    val onDots = ev.x > width - 120 && ev.y > height - 170 && ev.y < height - 60
                    if (long || onDots) menu(e.book) else open(e.book.id)
                }
            }
            return true
        }
    }

    private object Icons { fun dots(c: Canvas, x: Float, y: Float) { val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }; for (i in -1..1) c.drawCircle(x + i * 14f, y, 4f, p) } }

    // ---- actions ----------------------------------------------------------------------------------------------------------------

    private var lastOpen = 0L

    private fun open(id: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOpen < 1500) return
        lastOpen = now
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("book", id))
    }

    private fun menu(b: Book) {
        sheet.list(b.title.take(40), listOf(
            Row("Open") { open(b.id) },
            Row("Rename") { sheet.input("Title", b.title) { t -> if (t.isNotBlank()) { b.title = t.trim(); BookStore.save(this, b); refresh() } } },
            Row("Remove from the library", "Deletes this copy of the book and its highlights; the original file stays where it is") {
                sheet.confirm("Remove this book?", "\"${b.title}\", its highlights and saved words will be removed from the app.", "Remove") { BookStore.remove(this, b.id); refresh() }
            }
        ))
    }

    // ---- adding books ---------------------------------------------------------------------------------------------------------------

    private fun pickBooks() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        try { startActivityForResult(i, REQ_PICK) } catch (_: Throwable) { notice.show("No file picker found on this tablet") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            addAll(uris.map { it to displayName(it) })
        }
    }

    private fun displayName(uri: Uri): String {
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } } } catch (_: Throwable) { }
        return uri.lastPathSegment ?: "Book"
    }

    /** "Open with Ink Notes" for ebook files from a file manager. */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) { Intent.ACTION_VIEW -> i.data; Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM); else -> null }
        if (uri != null) { i.action = null; addAll(listOf(uri to displayName(uri))) }
    }

    private fun addAll(items: List<Pair<Uri, String>>) {
        if (busy || items.isEmpty()) return
        busy = true; busyPanel.visibility = View.VISIBLE
        thread(name = "book-import") {
            var added = 0; var existing = 0; val failed = ArrayList<String>(); var last: Book? = null
            for ((i, it) in items.withIndex()) {
                ui.post { busyText.text = "Adding books...\n\n${i + 1} of ${items.size}: ${it.second.take(60)}" }
                try {
                    val r = BookImporter.importBook(this, it.first, it.second)
                    if (r.isNew) added++ else existing++
                    last = r.book
                } catch (e: Throwable) { failed.add("${it.second.take(30)}: ${e.message}"); Log.w("Books", "import failed", e) }
            }
            ui.post {
                busy = false; busyPanel.visibility = View.GONE
                val msg = StringBuilder()
                if (added > 0) msg.append("Added $added ${if (added == 1) "book" else "books"}. ")
                if (existing > 0) msg.append("$existing ${if (existing == 1) "was" else "were"} already in the library. ")
                if (failed.isNotEmpty()) msg.append("Could not add: ").append(failed.joinToString("; "))
                notice.show(msg.toString().trim().ifEmpty { "Nothing was added" }, 7000)
                refresh()
            }
        }
    }

    // ---- finding ebook files on the tablet ----------------------------------------------------------------------------------------------

    private class Found(val file: File, val size: Long)

    private fun findOnTablet() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingScan = true
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        scan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (pendingScan) scan() }
            else notice.show("To list the ebook files on this tablet, allow access to files. You can still use Add books.")
            pendingScan = false
        }
    }

    private fun scan() {
        if (busy) return
        busy = true; busyPanel.visibility = View.VISIBLE; busyText.text = "Looking for ebook files on this tablet..."
        thread(name = "book-scan") {
            val found = ArrayList<Found>()
            val root = Environment.getExternalStorageDirectory()
            var count = 0
            fun walk(d: File, depth: Int) {
                if (depth > 8 || found.size >= 800) return
                val children = d.listFiles() ?: return
                for (f in children) {
                    val name = f.name
                    if (f.isDirectory) {
                        if (name.startsWith(".") || name == "Android" || name == "Ink Notes" || name.equals("cache", true)) continue
                        walk(f, depth + 1)
                    } else {
                        count++
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in BookImporter.EXTENSIONS) continue
                        val size = f.length()
                        if (size < 2048) continue
                        if ((ext == "txt" || ext == "html" || ext == "htm" || ext == "xhtml") && size < 40_000) continue      // short notes are not books
                        found.add(Found(f, size))
                    }
                }
            }
            walk(root, 0)
            found.sortBy { it.file.name.lowercase() }
            ui.post {
                busy = false; busyPanel.visibility = View.GONE
                if (found.isEmpty()) notice.show("No ebook files were found on this tablet (looked through $count files)", 6000)
                else showFound(found, HashSet(), 0)
            }
        }
    }

    private fun showFound(found: List<Found>, picked: MutableSet<String>, page: Int) {
        val fmtSize = { b: Long -> if (b >= 1 shl 20) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB" }
        val rows = found.map { f ->
            val path = f.file.absolutePath
            Row((if (path in picked) "[x]  " else "[  ]  ") + f.file.name, "${f.file.parentFile?.absolutePath?.removePrefix(Environment.getExternalStorageDirectory().absolutePath)?.ifEmpty { "/" }} · ${fmtSize(f.size)}", keepOpen = true) {
                if (!picked.add(path)) picked.remove(path)
                val per = Math.max(3, ((frame.height * 0.72f - 2 * Eink.BAR_H) / Eink.ROW_H).toInt())
                showFound(found, picked, found.indexOfFirst { it.file.absolutePath == path } / per)
            }
        }
        val footer = listOf(
            EButton(this, null, if (picked.size == found.size) "None" else "Select all") {
                if (picked.size == found.size) picked.clear() else picked.addAll(found.map { it.file.absolutePath })
                showFound(found, picked, page)
            }.apply { textSize = 24f },
            EButton(this, null, "Add ${picked.size}") {
                sheet.hide()
                addAll(found.filter { it.file.absolutePath in picked }.map { Uri.fromFile(it.file) to it.file.name })
            }.apply { textSize = 24f; enabledState = picked.isNotEmpty() })
        sheet.list("Ebook files on this tablet (${found.size})", rows, page, footer)
    }

    // ---- debug ------------------------------------------------------------------------------------------------------------------------------

    /** Debug builds only: adb shell am broadcast -a app.einknotes.BOOKS --es cmd add_path --es path /sdcard/Download/x.epub */
    private fun registerDebug() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getStringExtra("cmd")) {
                    "add_path" -> i.getStringExtra("path")?.let { p -> addAll(listOf(Uri.fromFile(File(p)) to File(p).name)) }
                    "list" -> Log.d("InkDebug", "books: " + BookStore.list(this@BooksActivity).joinToString { "${it.id}='${it.title}' ${it.format} spine=${it.spine.size} toc=${it.toc.size} cover=${it.cover}" })
                    "open" -> i.getStringExtra("id")?.let { open(it) }
                    "delete" -> i.getStringExtra("id")?.let { BookStore.remove(this@BooksActivity, it); refresh() }
                    "scan" -> findOnTablet()
                    "sheet_close" -> sheet.hide()
                }
            }
        }
        registerReceiver(debugReceiver, IntentFilter("app.einknotes.BOOKS"))
    }

    override fun onDestroy() {
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        super.onDestroy()
    }
}
