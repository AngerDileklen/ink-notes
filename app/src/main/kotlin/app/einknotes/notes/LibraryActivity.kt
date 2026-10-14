package app.einknotes.notes

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Icons
import app.einknotes.notes.ui.Notice
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The home screen. Notebooks are cards in a grid that turns page by page (no scrolling: it smears on e-ink).
 * Sections: All, Recent, Favorites, a folder, Recently deleted. Every action is in a flat sheet.
 */
class LibraryActivity : Activity() {

    private class Entry(val id: String, val file: File, val header: NoteStore.Header, val damaged: Boolean = false)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var pdfs: PdfLibrary
    private lateinit var frame: FrameLayout
    private lateinit var sheet: Sheet
    private lateinit var notice: Notice
    private lateinit var grid: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var prevBtn: EButton
    private lateinit var nextBtn: EButton
    private lateinit var sectionBar: LinearLayout
    private lateinit var sortBtn: EButton
    private lateinit var busyPanel: LinearLayout
    private lateinit var busyText: TextView
    private val dir by lazy { File(filesDir, "notes") }
    private val trash by lazy { File(dir, "trash").also { it.mkdirs() } }
    private var busy = false
    private val REQ_PDF = 51
    private val REQ_BACKUP = 52
    private val prefs get() = getSharedPreferences("library", MODE_PRIVATE)

    // what is shown
    private val ALL = "*"; private val RECENT = "#recent"; private val FAVORITES = "#fav"; private val UNFILED = "-"; private val DELETED = "#deleted"
    private var section = ALL
    private var gridPage = 0
    private val COLS = 3
    private val ROWS = 2

    private fun folders(): List<String> = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).sortedBy { it.lowercase() }
    private fun folderOf(id: String): String = prefs.getString("folder_$id", "") ?: ""
    private fun isFavorite(id: String) = prefs.getBoolean("fav_$id", false)
    private fun isFolder(s: String) = s != ALL && s != RECENT && s != FAVORITES && s != UNFILED && s != DELETED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfs = PdfLibrary(this)
        section = prefs.getString("section", ALL) ?: ALL

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(16, 8, 16, 8) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Library", 30f, bold = true).apply { setPadding(12, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(btn("plus", "New") { newNotebook() }, LinearLayout.LayoutParams(130, Eink.BAR_H + 8))
        head.addView(btn("pdf", "Import PDF") { pickPdf() }, LinearLayout.LayoutParams(170, Eink.BAR_H + 8))
        head.addView(btn("export", "Restore") { pickBackup() }, LinearLayout.LayoutParams(150, Eink.BAR_H + 8))
        root.addView(head)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(EButton(this, null, "Notebooks") { }.apply { textSize = 28f; selectedState = true }, LinearLayout.LayoutParams(0, Eink.BAR_H - 10, 1f).apply { setMargins(0, 4, 6, 4) })
        tabs.addView(EButton(this, null, "Books") { startActivity(Intent(this, app.einknotes.reader.BooksActivity::class.java)); overridePendingTransition(0, 0) }.apply { textSize = 28f }, LinearLayout.LayoutParams(0, Eink.BAR_H - 10, 1f).apply { setMargins(6, 4, 0, 4) })
        root.addView(tabs)

        sectionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(sectionBar, LinearLayout.LayoutParams(-1, Eink.BAR_H).apply { topMargin = 8 })
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 3).apply { topMargin = 8 })

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 0) }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prevBtn = EButton(this, "left", null) { gridPage--; refresh() }
        nextBtn = EButton(this, "right", null) { gridPage++; refresh() }
        pageLabel = label("", 22f).apply { gravity = Gravity.CENTER }
        sortBtn = EButton(this, null, "Sort") { chooseSort() }.apply { textSize = 24f }
        pager.addView(sortBtn, LinearLayout.LayoutParams(280, Eink.BAR_H))
        pager.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pager.addView(prevBtn, LinearLayout.LayoutParams(150, Eink.BAR_H))
        pager.addView(pageLabel, LinearLayout.LayoutParams(200, -2))
        pager.addView(nextBtn, LinearLayout.LayoutParams(150, Eink.BAR_H))
        root.addView(pager)

        frame = FrameLayout(this)
        frame.addView(root)
        busyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true
            setPadding(60, 400, 60, 60); visibility = View.GONE
        }
        busyText = label("", 22f)
        busyPanel.addView(busyText)
        frame.addView(busyPanel)
        setContentView(frame)
        sheet = Sheet(this, frame)
        notice = Notice(frame)
        purgeOldTrash()
        handleIncoming(intent)
        registerDebugControl()
    }

    private fun btn(icon: String?, text: String?, onClick: () -> Unit) = EButton(this, icon, text) { onClick() }.apply { bordered = false }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent) }

    override fun onResume() { super.onResume(); refresh() }

    @Deprecated("Back closes an open sheet first")
    override fun onBackPressed() { if (sheet.isShowing) sheet.hide() else super.onBackPressed() }

    // ---- list --------------------------------------------------------------------------------

    private fun entriesIn(d: File): List<Entry> {
        val files = d.listFiles { f -> Regex("^[A-Za-z0-9_]+\\.inkn$").matches(f.name) } ?: return emptyList()
        return files.map { f ->
            val id = f.name.removeSuffix(".inkn")
            val h = NoteStore.readHeader(f)
            // A damaged notebook stays visible: opening it offers its backups.
            if (h == null) Entry(id, f, NoteStore.Header("Damaged notebook ($id)", f.lastModified(), f.lastModified(), 0), damaged = true)
            else Entry(id, f, h)
        }
    }

    private fun entries(): List<Entry> = entriesIn(dir)

    private fun sorted(list: List<Entry>): List<Entry> = when (prefs.getString("sort", "edited")) {
        "created" -> list.sortedByDescending { it.header.created }
        "az" -> list.sortedBy { it.header.title.lowercase() }
        "za" -> list.sortedByDescending { it.header.title.lowercase() }
        else -> list.sortedByDescending { it.header.modified }
    }

    private fun shown(): List<Entry> = when (section) {
        DELETED -> entriesIn(trash).sortedByDescending { prefs.getLong("deletedAt_${it.id}", 0) }
        RECENT -> entries().filter { prefs.getLong("opened_${it.id}", 0) > 0 }.sortedByDescending { prefs.getLong("opened_${it.id}", 0) }.take(12)
        FAVORITES -> sorted(entries().filter { isFavorite(it.id) })
        UNFILED -> sorted(entries().filter { folderOf(it.id).isEmpty() })
        ALL -> sorted(entries())
        else -> sorted(entries().filter { folderOf(it.id) == section })
    }

    private fun sectionName(s: String) = when (s) {
        ALL -> "All"; RECENT -> "Recent"; FAVORITES -> "Favorites"; UNFILED -> "No folder"; DELETED -> "Recently deleted"; else -> s
    }

    private fun buildSections() {
        sectionBar.removeAllViews()
        fun chip(text: String, key: String) {
            val b = EButton(this, null, text) { section = key; gridPage = 0; prefs.edit().putString("section", key).apply(); refresh() }
            b.textSize = 26f; b.selectedState = section == key; b.bordered = false
            sectionBar.addView(b, LinearLayout.LayoutParams(-2, Eink.BAR_H).apply { rightMargin = 6 })
        }
        chip("All", ALL); chip("Recent", RECENT); chip("Favorites", FAVORITES)
        val inFolder = isFolder(section) || section == UNFILED
        val f = EButton(this, null, if (inFolder) "Folder: ${sectionName(section)}" else "Folders...") { chooseFolderSection() }
        f.textSize = 26f; f.selectedState = inFolder; f.bordered = false
        sectionBar.addView(f, LinearLayout.LayoutParams(-2, Eink.BAR_H).apply { rightMargin = 6 })
        sectionBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        chip("Recently deleted", DELETED)
    }

    private fun refresh() {
        if (!::grid.isInitialized) return
        if (isFolder(section) && section !in folders()) section = ALL
        buildSections()
        sortBtn.label = "Sort: " + when (prefs.getString("sort", "edited")) { "created" -> "created"; "az" -> "A to Z"; "za" -> "Z to A"; else -> "last edited" }
        sortBtn.enabledState = section != RECENT && section != DELETED
        val list = shown()
        val per = COLS * ROWS
        val pages = maxOf(1, (list.size + per - 1) / per)
        gridPage = gridPage.coerceIn(0, pages - 1)
        pageLabel.text = "${gridPage + 1} / $pages"
        prevBtn.enabledState = gridPage > 0
        nextBtn.enabledState = gridPage < pages - 1
        grid.removeAllViews()
        if (list.isEmpty()) {
            grid.addView(label(when (section) {
                DELETED -> "Nothing here. Deleted notebooks stay here for 30 days, then they are removed."
                FAVORITES -> "No favorites yet. Open a notebook's menu (the dots on its card) and choose Add to favorites."
                RECENT -> "Notebooks you open will appear here."
                ALL -> "No notebooks yet.\n\nTap New to start writing, or Import PDF to annotate a document."
                else -> "This folder is empty. Use Move to folder in a notebook's menu to put one here."
            }, 20f).apply { setPadding(16, 40, 16, 16) })
            return
        }
        val pageItems = list.drop(gridPage * per).take(per)
        for (r in 0 until ROWS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until COLS) {
                val e = pageItems.getOrNull(r * COLS + c)
                val v: View = if (e != null) Card(this, e) else View(this)
                row.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(10, 10, 10, 10) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        makeMissingThumbnails(list)
    }

    private var drawingThumbnails = false

    private fun makeMissingThumbnails(list: List<Entry>) {
        if (section == DELETED) return
        val missing = list.filter { !it.damaged && !File(dir, "${it.id}.png").exists() }
        if (missing.isEmpty() || drawingThumbnails) return
        drawingThumbnails = true
        thread(name = "thumbnails") {
            for (e in missing) try {
                val n = NoteStore.load(e.file)
                Thumbnailer.save(n.pages[0], pdfs, 360, File(dir, "${e.id}.png"))
            } catch (_: Throwable) { }
            ui.post { drawingThumbnails = false; refresh() }
        }
    }

    /** One notebook: picture of the first page, title, details. Tap opens; the dots (or a long press) open its menu. */
    private inner class Card(ctx: Context, val e: Entry) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val thumb: Bitmap? = run {
            val f = File(if (section == DELETED) trash else dir, "${e.id}.png")
            if (f.exists()) try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } else null
        }
        private val fmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        private var downAt = 0L

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val textH = 124f
            p.style = Paint.Style.STROKE; p.strokeWidth = Eink.BORDER; p.color = Color.BLACK
            c.drawRoundRect(RectF(2f, 2f, w - 2f, h - 2f), Eink.RADIUS, Eink.RADIUS, p)
            // page picture, kept in proportion
            val box = RectF(24f, 20f, w - 24f, h - textH - 6f)
            val b = thumb
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Eink.GREY
            if (b != null) {
                val s = minOf(box.width() / b.width, box.height() / b.height)
                val dw = b.width * s; val dh = b.height * s
                val dst = RectF(box.centerX() - dw / 2, box.top, box.centerX() + dw / 2, box.top + dh)
                c.drawBitmap(b, Rect(0, 0, b.width, b.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawRect(dst, p)
            } else {
                val dw = box.height() * 0.75f
                c.drawRect(box.centerX() - dw / 2, box.top, box.centerX() + dw / 2, box.bottom, p)
            }
            // text
            p.style = Paint.Style.FILL; p.color = Color.BLACK; p.textSize = 30f; p.typeface = Typeface.DEFAULT_BOLD
            var title = e.header.title
            val maxW = w - 110f
            while (title.length > 1 && p.measureText(title) > maxW) title = title.dropLast(2) + "…"
            c.drawText(title, 24f, h - textH + 44f, p)
            p.typeface = Typeface.DEFAULT; p.textSize = 22f; p.color = Eink.GREY
            val detail = if (section == DELETED) "deleted ${fmt.format(Date(prefs.getLong("deletedAt_${e.id}", 0)))}"
                else "${e.header.pageCount} ${if (e.header.pageCount == 1) "page" else "pages"} · ${fmt.format(Date(e.header.modified))}"
            c.drawText(detail, 24f, h - textH + 80f, p)
            val folder = folderOf(e.id)
            if (folder.isNotEmpty() && section != DELETED) c.drawText(folder, 24f, h - textH + 110f, p)
            p.color = Color.BLACK
            if (isFavorite(e.id) && section != DELETED) Icons.draw(c, "starfill", w - 48f, 48f, 40f, stroke, p)
            Icons.draw(c, "more", w - 54f, h - textH + 34f, 44f, stroke, p)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> downAt = SystemClock.uptimeMillis()
                MotionEvent.ACTION_UP -> {
                    val long = SystemClock.uptimeMillis() - downAt > 550
                    val onDots = ev.x > width - 120 && ev.y > height - 140
                    if (section == DELETED || long || onDots) cardMenu(e) else open(e.id)
                }
            }
            return true
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    private var lastOpenAt = 0L

    private fun open(id: String) {
        // a double tap must not start two editors on the same notebook
        val now = SystemClock.uptimeMillis()
        if (now - lastOpenAt < 1500) return
        lastOpenAt = now
        prefs.edit().putLong("opened_$id", System.currentTimeMillis()).apply()
        startActivity(Intent(this, NotesActivity::class.java).putExtra("notebook", id))
    }

    private fun newNotebook() {
        val id = "nb_" + System.currentTimeMillis()
        NoteStore.save(NoteData("Untitled"), File(dir, "$id.inkn"))
        if (isFolder(section)) prefs.edit().putString("folder_$id", section).apply()
        open(id)
    }

    private fun cardMenu(e: Entry) {
        if (section == DELETED) {
            sheet.list(e.header.title, listOf(
                Row("Put back in the library") { restoreFromTrash(e) },
                Row("Delete for good", "Cannot be undone") {
                    sheet.confirm("Delete for good?", "\"${e.header.title}\" and its pages will be removed permanently.", "Delete for good") { deleteForGood(e.id) }
                }
            ))
            return
        }
        val fav = isFavorite(e.id)
        sheet.list(e.header.title, listOf(
            Row("Open") { open(e.id) },
            Row("Rename") { rename(e) },
            Row(if (fav) "Remove from favorites" else "Add to favorites") { prefs.edit().putBoolean("fav_${e.id}", !fav).apply(); refresh() },
            Row("Duplicate") { duplicate(e) },
            Row("Move to folder", folderOf(e.id).ifEmpty { "No folder" }) { chooseFolderFor(e) },
            Row("Export as PDF", "Saved to Download/Ink Notes") { exportPdf(e) },
            Row("Back up", "A .inkz file with its PDFs, pictures and audio") { backUp(e) },
            Row("Delete", "Kept in Recently deleted for 30 days") { moveToTrash(e) }
        ))
    }

    private fun rename(e: Entry) {
        sheet.input("Rename notebook", e.header.title) { t ->
            val name = t.trim().ifEmpty { "Untitled" }
            try { NoteStore.rename(e.file, name) } catch (_: Throwable) { notice.show("That notebook could not be read, so it was not renamed") }
            refresh()
        }
    }

    private fun duplicate(e: Entry) {
        try {
            val n = NoteStore.load(e.file)
            n.title = n.title + " copy"
            n.recordings.clear()          // the sound stays with the original
            for (p in n.pages) for (it in p.items) { it.recId = 0; it.recMs = 0 }
            val id = "nb_" + System.currentTimeMillis()
            NoteStore.save(n, File(dir, "$id.inkn"))
            File(dir, "${e.id}.png").takeIf { it.exists() }?.copyTo(File(dir, "$id.png"))
            folderOf(e.id).takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("folder_$id", it).apply() }
            notice.show("Made \"${n.title}\"")
        } catch (t: Throwable) { notice.show("Could not duplicate: ${t.message}") }
        refresh()
    }

    /** Soft delete: the notebook's files move to notes/trash and can come back for 30 days. */
    private fun moveToTrash(e: Entry) {
        for (f in dir.listFiles() ?: emptyArray()) if (f.isFile && f.name.startsWith(e.id + ".")) f.renameTo(File(trash, f.name))
        prefs.edit().putLong("deletedAt_${e.id}", System.currentTimeMillis()).apply()
        notice.show("\"${e.header.title}\" moved to Recently deleted")
        refresh()
    }

    private fun restoreFromTrash(e: Entry) {
        if (File(dir, "${e.id}.inkn").exists()) { notice.show("A notebook with the same name is already in the library"); return }
        for (f in trash.listFiles() ?: emptyArray()) if (f.name.startsWith(e.id + ".")) f.renameTo(File(dir, f.name))
        prefs.edit().remove("deletedAt_${e.id}").apply()
        notice.show("\"${e.header.title}\" is back in the library")
        refresh()
    }

    private fun deleteForGood(id: String) {
        for (f in trash.listFiles() ?: emptyArray()) if (f.name.startsWith("$id.")) f.delete()
        prefs.edit().remove("deletedAt_$id").remove("folder_$id").remove("fav_$id").remove("opened_$id").apply()
        collectMediaGarbage()
        refresh()
    }

    private fun purgeOldTrash() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        var removed = false
        for (e in entriesIn(trash)) {
            val at = prefs.getLong("deletedAt_${e.id}", 0)
            if (at in 1 until cutoff) {
                for (f in trash.listFiles() ?: emptyArray()) if (f.name.startsWith(e.id + ".")) f.delete()
                prefs.edit().remove("deletedAt_${e.id}").apply()
                removed = true
            }
        }
        if (removed) collectMediaGarbage()
    }

    /** Removes PDFs, pictures and audio that no notebook (in the library or in Recently deleted) uses any more. */
    private fun collectMediaGarbage() {
        val usedPdfs = HashSet<String>(); val usedImages = HashSet<String>(); val usedAudio = HashSet<String>()
        var allRead = true
        for (x in entries() + entriesIn(trash)) try {
            val n = NoteStore.load(x.file)
            n.pages.forEach { p ->
                p.pdfFile?.let { usedPdfs.add(it) }
                p.items.forEach { item -> if (item is ImageItem) usedImages.add(item.file) }
            }
            n.recordings.forEach { r -> usedAudio.add(r.file) }
        } catch (_: Throwable) { allRead = false }
        // If any notebook could not be read we cannot know what it uses, so nothing is cleaned up.
        if (allRead) {
            pdfs.collectGarbage(usedPdfs)
            ImageStore.init(this); ImageStore.collectGarbage(usedImages)
            AudioNotes.collectGarbage(this, usedAudio)
        }
    }

    private fun chooseSort() {
        sheet.choose("Sort notebooks", listOf("Last edited" to "edited", "Created" to "created", "Name A to Z" to "az", "Name Z to A" to "za"),
            prefs.getString("sort", "edited")) { prefs.edit().putString("sort", it).apply(); refresh() }
    }

    // ---- folders -----------------------------------------------------------------------------

    private fun newFolder(then: (String) -> Unit) {
        sheet.input("New folder", "", hint = "Folder name") { t ->
            val name = t.trim().take(40)
            if (name.isEmpty() || name.startsWith("#") || name == ALL || name == UNFILED) return@input
            prefs.edit().putStringSet("folders", (folders() + name).toSet()).apply()
            then(name)
        }
    }

    private fun showSection(s: String) { section = s; gridPage = 0; prefs.edit().putString("section", s).apply(); refresh() }

    private fun chooseFolderSection() {
        val all = entries()
        val rows = ArrayList<Row>()
        rows.add(Row("No folder", "${all.count { folderOf(it.id).isEmpty() }} notebooks", selected = section == UNFILED) { showSection(UNFILED) })
        for (f in folders()) rows.add(Row(f, "${all.count { folderOf(it.id) == f }} notebooks", selected = section == f) { showSection(f) })
        rows.add(Row("New folder...") { newFolder { showSection(it) } })
        if (section in folders()) {
            val gone = section
            rows.add(Row("Delete folder \"$gone\"", "Its notebooks stay in the library") {
                val ed = prefs.edit().putStringSet("folders", (folders() - gone).toSet())
                for (x in all) if (folderOf(x.id) == gone) ed.remove("folder_${x.id}")
                ed.apply(); showSection(ALL)
            })
        }
        sheet.list("Folders", rows)
    }

    private fun chooseFolderFor(e: Entry) {
        val rows = ArrayList<Row>()
        val cur = folderOf(e.id)
        rows.add(Row("No folder", selected = cur.isEmpty()) { prefs.edit().remove("folder_${e.id}").apply(); refresh() })
        for (f in folders()) rows.add(Row(f, selected = cur == f) { prefs.edit().putString("folder_${e.id}", f).apply(); refresh() })
        rows.add(Row("New folder...") { newFolder { prefs.edit().putString("folder_${e.id}", it).apply(); refresh() } })
        sheet.list("Move \"${e.header.title}\" to", rows)
    }

    // ---- backup, restore, export -------------------------------------------------------------

    private fun busy(text: String) { busy = true; busyText.text = text; busyPanel.visibility = View.VISIBLE }
    private fun done(message: String?) { busy = false; busyPanel.visibility = View.GONE; message?.let { notice.show(it, 5000) } }

    private fun backUp(e: Entry) {
        if (busy) return
        busy("Backing up \"${e.header.title}\"...")
        thread(name = "backup") {
            try {
                val r = NotebookBundle.export(this, e.file)
                Log.d("InkDebug", "backup ${r.uri} ${r.name} ${r.bytes}")
                ui.post { done("Saved Download/Ink Notes/${r.name} (${"%.1f".format(r.bytes / 1048576.0)} MB)") }
            } catch (t: Throwable) { ui.post { done("Could not back up: ${t.message}") } }
        }
    }

    private fun exportPdf(e: Entry) {
        if (busy) return
        busy("Exporting \"${e.header.title}\"...")
        thread(name = "export") {
            try {
                val n = NoteStore.load(e.file)
                val r = PdfExporter.export(this, n, pdfs) { i, total -> ui.post { busyText.text = "Exporting \"${e.header.title}\"...\n\npage $i of $total" } }
                Log.d("InkDebug", "exported ${r.uri} ${r.pages} pages ${r.bytes} bytes name=${r.name}")
                ui.post { done("Saved Download/Ink Notes/${r.name} (${"%.1f".format(r.bytes / 1048576.0)} MB)") }
            } catch (t: Throwable) { ui.post { done("Could not export: ${t.message}") } }
        }
    }

    private fun pickBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(i, REQ_BACKUP) } catch (_: Throwable) { notice.show("No file picker found on this tablet") }
    }

    private fun restore(uri: Uri) {
        if (busy) return
        busy("Restoring notebook...")
        thread(name = "restore") {
            try {
                NotebookBundle.import(this, uri, "nb_" + System.currentTimeMillis())
                ui.post { done("Notebook restored"); refresh() }
            } catch (t: Throwable) { ui.post { done("Could not restore: ${t.message}") } }
        }
    }

    // ---- PDF import --------------------------------------------------------------------------

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf" }
        try { startActivityForResult(i, REQ_PDF) } catch (_: Throwable) { notice.show("No file picker found on this tablet") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PDF && resultCode == RESULT_OK) data?.data?.let { importPdf(it) }
        if (requestCode == REQ_BACKUP && resultCode == RESULT_OK) data?.data?.let { restore(it) }
    }

    /** "Open with Ink Notes" from a file manager, or a PDF shared to the app. */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) { i.action = null; importPdf(uri) }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Throwable) { }
        return uri.lastPathSegment ?: "Imported PDF"
    }

    /** Copies the PDF in and makes a new notebook with one page per PDF page, named after the file. */
    private fun importPdf(uri: Uri) {
        if (busy) return
        busy("Importing PDF...")
        val title = displayName(uri).removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Imported PDF" }
        thread(name = "pdf-import") {
            try {
                val name = pdfs.importFrom(this, uri)
                val pages = pdfs.buildPages(name) { i, n -> ui.post { busyText.text = "Importing PDF...\n\nreading page $i of $n" } }
                val note = NoteData(title)
                note.pages.clear(); note.pages.addAll(pages)
                val id = "nb_" + System.currentTimeMillis()
                NoteStore.save(note, File(dir, "$id.inkn"))
                try { Thumbnailer.save(pages[0], pdfs, 360, File(dir, "$id.png")) } catch (_: Throwable) { }
                ui.post { done(null); open(id) }
            } catch (e: Throwable) {
                ui.post { done(e.message ?: "Could not import that PDF") }
            }
        }
    }

    // ---- debug -------------------------------------------------------------------------------

    private var debugReceiver: BroadcastReceiver? = null

    /** Debug builds only: adb shell am broadcast -a app.einknotes.LIBRARY --es cmd <command> */
    private fun registerDebugControl() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val id = i.getStringExtra("id")
                when (i.getStringExtra("cmd")) {
                    "restore_path" -> i.getStringExtra("path")?.let { restore(Uri.fromFile(File(it))) }
                    "backup" -> entries().firstOrNull { it.id == id }?.let { backUp(it) }
                    "list" -> Log.d("InkDebug", "library: " + entries().joinToString { "${it.id}='${it.header.title}' pages=${it.header.pageCount} folder='${folderOf(it.id)}' fav=${isFavorite(it.id)}" } +
                        " | trash: " + entriesIn(trash).joinToString { it.id })
                    "folder" -> { id ?: return; val f = i.getStringExtra("folder") ?: ""
                        if (f.isEmpty()) prefs.edit().remove("folder_$id").apply()
                        else prefs.edit().putString("folder_$id", f).putStringSet("folders", (folders() + f).toSet()).apply()
                        refresh() }
                    "section" -> showSection(i.getStringExtra("v") ?: ALL)
                    "fav" -> { id ?: return; prefs.edit().putBoolean("fav_$id", i.getBooleanExtra("v", true)).apply(); refresh() }
                    "menu" -> (entries() + entriesIn(trash)).firstOrNull { it.id == id }?.let { cardMenu(it) }
                    "trash" -> entries().firstOrNull { it.id == id }?.let { moveToTrash(it) }
                    "untrash" -> entriesIn(trash).firstOrNull { it.id == id }?.let { restoreFromTrash(it) }
                    "purge" -> id?.let { deleteForGood(it) }
                    "duplicate" -> entries().firstOrNull { it.id == id }?.let { duplicate(it) }
                    "page" -> { gridPage = i.getIntExtra("v", 0); refresh() }
                    "sheet_close" -> sheet.hide()
                }
            }
        }
        registerReceiver(debugReceiver, IntentFilter("app.einknotes.LIBRARY"))
    }

    override fun onDestroy() {
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        super.onDestroy()
    }
}
