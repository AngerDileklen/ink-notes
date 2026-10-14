package app.einknotes.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The home screen: every notebook with a picture of its first page. Plain lists and borders, no animation,
 * so the e-ink panel only redraws when something really changes.
 */
class LibraryActivity : Activity() {

    private class Entry(val id: String, val file: File, val header: NoteStore.Header)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var pdfs: PdfLibrary
    private lateinit var listBox: LinearLayout
    private lateinit var busyPanel: LinearLayout
    private lateinit var busyText: TextView
    private val dir by lazy { File(filesDir, "notes") }
    private var busy = false
    private val REQ_PDF = 51

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfs = PdfLibrary(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 18, 24, 6) }
        head.addView(TextView(this).apply { text = "Library"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("New notebook") { newNotebook() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("Import PDF...") { pickPdf() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 24) }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val frame = android.widget.FrameLayout(this)
        frame.addView(root)
        busyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true
            setPadding(60, 400, 60, 60); visibility = View.GONE
        }
        busyText = TextView(this).apply { textSize = 22f; setTextColor(Color.BLACK) }
        busyPanel.addView(busyText)
        frame.addView(busyPanel)
        setContentView(frame)
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent) }

    override fun onResume() { super.onResume(); refresh() }

    // ---- list --------------------------------------------------------------------------------

    private fun entries(): List<Entry> {
        val files = dir.listFiles { f -> Regex("^[A-Za-z0-9_]+\\.inkn$").matches(f.name) } ?: return emptyList()
        return files.mapNotNull { f ->
            val h = NoteStore.readHeader(f) ?: return@mapNotNull null
            Entry(f.name.removeSuffix(".inkn"), f, h)
        }.sortedByDescending { it.header.modified }
    }

    private fun refresh() {
        listBox.removeAllViews()
        val all = entries()
        if (all.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "No notebooks yet.\n\nTap New notebook to start writing, or Import PDF to annotate a document."
                textSize = 18f; setTextColor(Color.BLACK); setPadding(12, 40, 12, 12)
            })
            return
        }
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        for (e in all) listBox.addView(row(e, fmt))
        // notebooks that have no picture yet get one, then the list is shown again
        val missing = all.filter { !File(dir, "${it.id}.png").exists() }
        if (missing.isNotEmpty() && !drawingThumbnails) {
            drawingThumbnails = true
            thread(name = "thumbnails") {
                for (e in missing) try {
                    val n = NoteStore.load(e.file)
                    Thumbnailer.save(n.pages[0], pdfs, 360, File(dir, "${e.id}.png"))
                } catch (_: Throwable) { }
                ui.post { drawingThumbnails = false; refresh() }
            }
        }
    }

    private var drawingThumbnails = false

    private fun row(e: Entry, fmt: SimpleDateFormat): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = border(); setPadding(14, 14, 14, 14)
            isClickable = true; setOnClickListener { open(e.id) }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE)
            val png = File(dir, "${e.id}.png")
            if (png.exists()) BitmapFactory.decodeFile(png.path)?.let { setImageBitmap(it) }
        }
        r.addView(thumb, LinearLayout.LayoutParams(190, 250))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 12, 0) }
        col.addView(TextView(this).apply { text = e.header.title; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK) })
        col.addView(TextView(this).apply {
            text = "${e.header.pageCount} ${if (e.header.pageCount == 1) "page" else "pages"}  ·  edited ${fmt.format(Date(e.header.modified))}"
            textSize = 15f; setTextColor(Color.BLACK); setPadding(0, 6, 0, 14)
        })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(button("Open") { open(e.id) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(button("Rename") { rename(e) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(button("Delete") { confirmDelete(e) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return LinearLayout(this).apply {
            setPadding(0, 8, 0, 8); addView(r, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun open(id: String) {
        startActivity(Intent(this, NotesActivity::class.java).putExtra("notebook", id))
    }

    private fun newNotebook() {
        val id = "nb_" + System.currentTimeMillis()
        NoteStore.save(NoteData("Untitled"), File(dir, "$id.inkn"))
        open(id)
    }

    private fun rename(e: Entry) {
        val input = EditText(this).apply { setText(e.header.title); setSingleLine(); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename notebook").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val t = input.text.toString().trim().ifEmpty { "Untitled" }
                try { NoteStore.rename(e.file, t) } catch (_: Throwable) { }
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setMessage("Delete \"${e.header.title}\" and all of its pages? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                for (f in dir.listFiles() ?: emptyArray()) if (f.name.startsWith(e.id + ".")) f.delete()
                // PDFs that no remaining notebook uses can go too
                val used = HashSet<String>()
                for (x in entries()) try { NoteStore.load(x.file).pages.forEach { p -> p.pdfFile?.let { used.add(it) } } } catch (_: Throwable) { }
                pdfs.collectGarbage(used)
                refresh()
            }
            .setNegativeButton("Keep", null).show()
    }

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf" }
        try { startActivityForResult(i, REQ_PDF) }
        catch (_: Throwable) { Toast.makeText(this, "No file picker found on this tablet", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PDF && resultCode == RESULT_OK) data?.data?.let { importPdf(it) }
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
        busy = true
        busyText.text = "Importing PDF..."
        busyPanel.visibility = View.VISIBLE
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
                ui.post { busy = false; busyPanel.visibility = View.GONE; open(id) }
            } catch (e: Throwable) {
                ui.post {
                    busy = false; busyPanel.visibility = View.GONE
                    Toast.makeText(this, e.message ?: "Could not import that PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- look --------------------------------------------------------------------------------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false; minHeight = 100; minimumHeight = 100
        stateListAnimator = null; setTextColor(Color.BLACK); background = border(); setPadding(4, 0, 4, 0)
        setOnClickListener { onClick() }
    }

    private fun border() = GradientDrawable().apply { setColor(Color.WHITE); setStroke(3, Color.BLACK); cornerRadius = 8f }
}
