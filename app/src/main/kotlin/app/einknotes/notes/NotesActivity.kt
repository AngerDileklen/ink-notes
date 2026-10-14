package app.einknotes.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.einknotes.EinkController
import app.einknotes.ProbeActivity
import java.io.File
import kotlin.concurrent.thread

/** In-app clipboard for items (works across pages and notebooks). */
object Clipboard {
    var items: List<Item> = emptyList()
}

/**
 * Ink Notes. Pen-only tablet, so everything is reachable with the pen; the hardware buttons add shortcuts.
 *
 * Buttons: Volume Up/Down turn pages (the factory settings of buttons 3 and 4). In the tablet's custom-key
 * settings you can also give buttons Zoom In/Out, arrow keys, Page Up/Down, Tab or Enter and they work here.
 */
class NotesActivity : Activity(), InkView.Host {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var note: NoteData
    private lateinit var ink: InkView
    private lateinit var pdfs: PdfLibrary
    private lateinit var importPanel: LinearLayout
    private lateinit var importText: TextView
    private var importing = false
    private lateinit var eink: EinkController
    private lateinit var file: File
    private lateinit var notebookName: String
    private val debuggableApp get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val prefs get() = getSharedPreferences("notes", Context.MODE_PRIVATE)

    // toolbar
    private val toolButtons = LinkedHashMap<Tool, Button>()
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button
    private lateinit var pageLabel: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var pagesPanel: PagesPanel
    private lateinit var inkModeBtn: Button
    private lateinit var polishBtn: Button
    private lateinit var paperBtn: Button
    private lateinit var sideBtn: Button
    private lateinit var snapBtn: Button
    private lateinit var devStatus: TextView

    // floating bars over the page
    private lateinit var optionsBar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var pasteBtn: Button
    private lateinit var selectionButtons: List<Button>

    // fast-ink presets: (label, mode while writing, mode afterwards)
    private val presets = listOf(
        Triple("off", null, null),
        Triple("AutoDU > GC16", EinkController.AUTO_DU, EinkController.PART_GC16),
        Triple("DU > GL16", EinkController.DU, EinkController.PART_GL16),
        Triple("A2 > GL16", EinkController.A2, EinkController.PART_GL16)
    )
    private var presetIndex = 1          // AutoDU > GC16 felt best on this tablet
    private var polish = false
    private var inFast = false

    private val settleToQuality = Runnable {
        val quality = presets[presetIndex].third ?: return@Runnable
        inFast = false
        eink.set(quality, "pen idle") {
            if (polish) ink.takePolishRect()?.let { r -> ink.invalidate(r) } else ink.takePolishRect()
        }
    }
    private val saveNow = Runnable { save() }

    // option presets
    private val widthNames = listOf("Fine", "Thin", "Normal", "Bold", "Heavy")
    private val widthValues = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.2f)
    private val penGrayNames = listOf("Black", "Dark", "Mid")
    private val penGrayValues = listOf(0, 60, 115)
    private val hlGrayNames = listOf("Light", "Medium", "Dark")
    private val hlGrayValues = listOf(205, 170, 130)
    private val angleNames = listOf("0", "45", "90", "135")
    private val angleValues = listOf(0f, 0.7854f, 1.5708f, 2.3562f)
    private val flatNames = listOf("Soft", "Medium", "Broad")
    private val flatValues = listOf(0.5f, 0.75f, 0.95f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eink = EinkController(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> eink.restoreNow(); prev?.uncaughtException(t, e) }

        presetIndex = prefs.getInt("preset", 1).coerceIn(0, presets.size - 1)
        polish = prefs.getBoolean("polish", false)

        // The library says which notebook to open (adb tests use e.g. --es notebook scratch so real notes stay untouched).
        notebookName = intent?.getStringExtra("notebook")?.filter { it.isLetterOrDigit() || it == '_' }
            ?.ifEmpty { null } ?: "notebook1"
        file = File(filesDir, "notes/$notebookName.inkn")
        rotateBackups()
        note = NoteStore.load(file)
        pdfs = PdfLibrary(this)
        ink = InkView(this, note, pdfs, this)
        ink.sideButtonTool = Tool.values().getOrElse(prefs.getInt("side", Tool.ERASER.ordinal)) { Tool.ERASER }
        loadToolSettings()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val row1 = row()
        for ((tool, label) in listOf(Tool.PEN to "Pen", Tool.HIGHLIGHTER to "Marker",
            Tool.ERASER to "Erase", Tool.SELECT to "Select", Tool.SHAPE to "Shape", Tool.HAND to "Hand")) {
            val b = tb(label) { onToolTapped(tool) }
            toolButtons[tool] = b
            row1.addView(b, weight())
        }
        undoBtn = tb("Undo") { ink.undo() }
        redoBtn = tb("Redo") { ink.redo() }
        row1.addView(undoBtn, weight()); row1.addView(redoBtn, weight())
        val row2 = row()
        pageLabel = tb("1/1") { showPages() }
        for (b in listOf(tb("<") { goPage(-1) }, pageLabel, tb(">") { goPage(1) }, tb("+ Page") { ink.addPageAfterCurrent() },
            tb("Zoom -") { ink.zoomBy(-1) }, tb("Zoom +") { ink.zoomBy(1) },
            tb("Refresh") { eink.fullRefresh() }, tb("More") { showSettings() })) row2.addView(b, weight())
        root.addView(row1); root.addView(row2)

        // the page, with floating bars on top so showing them never resizes (and re-renders) the page
        val pageArea = FrameLayout(this)
        pageArea.addView(ink, FrameLayout.LayoutParams(-1, -1))
        optionsBar = row().apply { visibility = View.GONE; setBackgroundColor(Color.WHITE) }
        pageArea.addView(optionsBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        selectionBar = buildSelectionBar().also { it.visibility = View.GONE }
        pageArea.addView(selectionBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(pageArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = FrameLayout(this)
        frame.addView(root)
        settingsPanel = buildSettings().also { it.visibility = View.GONE }
        frame.addView(settingsPanel)
        pagesPanel = PagesPanel(this, note, pdfs, ink, { hidePages() }, { i -> ink.goToPage(i); hidePages(); refreshChrome() })
        pagesPanel.visibility = View.GONE
        frame.addView(pagesPanel)
        importPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
            setPadding(60, 400, 60, 60)
            visibility = View.GONE
        }
        importText = TextView(this).apply { textSize = 22f; setTextColor(Color.BLACK) }
        importPanel.addView(importText)
        frame.addView(importPanel)
        setContentView(frame)

        selectTool(Tool.PEN)
        // come back to the page you were writing on
        ink.goToPage(prefs.getInt("lastPage_$notebookName", 0).coerceIn(0, note.pages.size - 1))
        refreshChrome()
        registerDebugControl()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    // ---- toolbar helpers ---------------------------------------------------------------------

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Plain bordered button. No ripple, no animation: those only cause extra e-ink refreshes. */
    private fun tb(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = 100
        minimumHeight = 100
        stateListAnimator = null
        setTextColor(Color.BLACK)
        background = border(false)
        setPadding(4, 0, 4, 0)
        setOnClickListener { onClick() }
    }

    private fun border(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) Color.BLACK else Color.WHITE)
        setStroke(3, Color.BLACK)
        cornerRadius = 8f
    }

    private fun mark(b: Button, selected: Boolean) {
        b.background = border(selected)
        b.setTextColor(if (selected) Color.WHITE else Color.BLACK)
    }

    // ---- tools -------------------------------------------------------------------------------

    /** Tapping the active tool again opens its options; tapping another tool switches to it. */
    private fun onToolTapped(t: Tool) {
        val current = if (ink.tool == Tool.CALLIGRAPHY) Tool.PEN else ink.tool
        if (current == t && (t == Tool.PEN || t == Tool.HIGHLIGHTER || t == Tool.SHAPE)) {
            if (optionsBar.visibility == View.VISIBLE) optionsBar.visibility = View.GONE else showOptions(t)
        } else selectTool(t)
    }

    private fun selectTool(t: Tool) {
        if (t != Tool.SELECT) ink.clearSelection()
        ink.tool = t
        for ((tool, b) in toolButtons) mark(b, tool == t || (tool == Tool.PEN && t == Tool.CALLIGRAPHY))
        optionsBar.visibility = View.GONE
        selectionBar.visibility = if (t == Tool.SELECT) View.VISIBLE else View.GONE
        updateSelectionBar()
    }

    private fun showOptions(t: Tool) {
        optionsBar.removeAllViews()
        val s = ink.settings
        fun cycle(label: String, names: List<String>, current: () -> Int, apply: (Int) -> Unit) {
            val b = tb("") { }
            fun text() { b.text = "$label: ${names[current()]}" }
            b.setOnClickListener { apply((current() + 1) % names.size); saveToolSettings(); text() }
            text()
            optionsBar.addView(b, weight())
        }
        fun idx(values: List<Float>, v: Float) = values.indices.minByOrNull { Math.abs(values[it] - v) } ?: 0
        fun idxI(values: List<Int>, v: Int) = values.indices.minByOrNull { Math.abs(values[it] - v) } ?: 0
        when (t) {
            Tool.PEN, Tool.CALLIGRAPHY -> {
                val b = tb("") { }
                b.text = "Style: " + (if (ink.tool == Tool.CALLIGRAPHY) "Calligraphy" else "Pen")
                b.setOnClickListener {
                    ink.tool = if (ink.tool == Tool.CALLIGRAPHY) Tool.PEN else Tool.CALLIGRAPHY
                    showOptions(ink.tool)
                }
                optionsBar.addView(b, weight())
                if (t == Tool.PEN) {
                    cycle("Width", widthNames, { idx(widthValues, s.penWidth) }) { s.penWidth = widthValues[it] }
                    cycle("Ink", penGrayNames, { idxI(penGrayValues, s.penGray) }) { s.penGray = penGrayValues[it] }
                } else {
                    cycle("Width", widthNames, { idx(widthValues, s.calWidth) }) { s.calWidth = widthValues[it] }
                    cycle("Ink", penGrayNames, { idxI(penGrayValues, s.calGray) }) { s.calGray = penGrayValues[it] }
                    cycle("Angle", angleNames, { idx(angleValues, s.calAngle) }) { s.calAngle = angleValues[it] }
                    cycle("Nib", flatNames, { idx(flatValues, s.calFlat) }) { s.calFlat = flatValues[it] }
                }
            }
            Tool.SHAPE -> {
                val kinds = UserShape.values().toList()
                cycle("Shape", kinds.map { it.label }, { kinds.indexOf(s.shapeTool) }) { s.shapeTool = kinds[it] }
                cycle("Width", widthNames, { idx(widthValues, s.penWidth) }) { s.penWidth = widthValues[it] }
                cycle("Ink", penGrayNames, { idxI(penGrayValues, s.penGray) }) { s.penGray = penGrayValues[it] }
            }
            Tool.HIGHLIGHTER -> {
                cycle("Width", widthNames, { idx(widthValues, s.hlWidth) }) { s.hlWidth = widthValues[it] }
                cycle("Tone", hlGrayNames, { idxI(hlGrayValues, s.hlGray) }) { s.hlGray = hlGrayValues[it] }
            }
            else -> return
        }
        optionsBar.visibility = View.VISIBLE
    }

    private fun loadToolSettings() {
        val s = ink.settings; val p = prefs
        s.penWidth = p.getFloat("penWidth", s.penWidth); s.penGray = p.getInt("penGray", s.penGray)
        s.hlWidth = p.getFloat("hlWidth", s.hlWidth); s.hlGray = p.getInt("hlGray", s.hlGray)
        s.calWidth = p.getFloat("calWidth", s.calWidth); s.calGray = p.getInt("calGray", s.calGray)
        s.calAngle = p.getFloat("calAngle", s.calAngle); s.calFlat = p.getFloat("calFlat", s.calFlat)
        s.shapeSnap = p.getBoolean("shapeSnap", true)
        s.shapeTool = UserShape.values().getOrElse(p.getInt("shapeTool", UserShape.RECT.ordinal)) { UserShape.RECT }
    }

    private fun saveToolSettings() {
        val s = ink.settings
        prefs.edit().putFloat("penWidth", s.penWidth).putInt("penGray", s.penGray).putFloat("hlWidth", s.hlWidth)
            .putInt("hlGray", s.hlGray).putFloat("calWidth", s.calWidth).putInt("calGray", s.calGray)
            .putFloat("calAngle", s.calAngle).putFloat("calFlat", s.calFlat)
            .putBoolean("shapeSnap", s.shapeSnap).putInt("shapeTool", s.shapeTool.ordinal).apply()
    }

    // ---- selection bar -----------------------------------------------------------------------

    private fun buildSelectionBar(): LinearLayout {
        val bar = row().apply { setBackgroundColor(Color.WHITE) }
        val del = tb("Delete") { ink.deleteSelection() }
        val copy = tb("Copy") { Clipboard.items = ink.copySelection(); updateSelectionBar() }
        val cut = tb("Cut") { Clipboard.items = ink.copySelection(); ink.deleteSelection(); updateSelectionBar() }
        val dup = tb("Duplicate") { ink.duplicateSelection() }
        pasteBtn = tb("Paste") { ink.paste(Clipboard.items) }
        val all = tb("All") { ink.selectAll() }
        val done = tb("Done") { ink.clearSelection() }
        selectionButtons = listOf(del, copy, cut, dup, done)
        for (b in listOf(all, del, copy, cut, dup, pasteBtn, done)) bar.addView(b, weight())
        return bar
    }

    private fun updateSelectionBar() {
        if (!::pasteBtn.isInitialized) return
        val has = ink.hasSelection
        for (b in selectionButtons) b.alpha = if (has) 1f else 0.35f
        pasteBtn.alpha = if (Clipboard.items.isNotEmpty()) 1f else 0.35f
    }

    override fun onSelectionChanged() { updateSelectionBar() }

    private fun refreshChrome() {
        pageLabel.text = "${ink.pageIndex + 1}/${note.pages.size}"
        undoBtn.alpha = if (ink.canUndo) 1f else 0.35f
        redoBtn.alpha = if (ink.canRedo) 1f else 0.35f
        if (::paperBtn.isInitialized) {
            inkModeBtn.text = "Ink mode: " + presets[presetIndex].first
            polishBtn.text = "Polish after writing: " + (if (polish) "on" else "off")
            paperBtn.text = "Paper: " + ink.page.paper.label
            snapBtn.text = "Hold pen to snap shapes: " + (if (ink.settings.shapeSnap) "on" else "off")
            sideBtn.text = "Pen side button: " + ink.sideButtonTool.name.lowercase()
        }
    }

    private fun goPage(delta: Int) {
        val n = ink.pageIndex + delta
        if (n in note.pages.indices) { ink.goToPage(n); refreshChrome() }
    }

    // ---- PDF import --------------------------------------------------------------------------

    private val REQ_PDF = 41

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
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

    /** Copies the PDF in, turns every page into a notebook page, and puts them after the current page. */
    private fun importPdf(uri: Uri) {
        if (importing) return
        importing = true
        hideSettings()
        importText.text = "Importing PDF..."
        importPanel.visibility = View.VISIBLE
        thread(name = "pdf-import") {
            try {
                val name = pdfs.importFrom(this, uri)
                val count = pdfs.pageCount(name)
                val pages = ArrayList<PageData>(count)
                for (i in 0 until count) {
                    val (wp, hp) = pdfs.pageSize(name, i)
                    val pg = PageData(Paper.BLANK)
                    pg.pdfFile = name; pg.pdfPage = i
                    pg.w = PAGE_W; pg.h = PAGE_W * hp / wp.toFloat()
                    pages.add(pg)
                    if (i % 10 == 0) ui.post { importText.text = "Importing PDF...\n\nreading page ${i + 1} of $count" }
                }
                ui.post {
                    ink.insertPagesAfterCurrent(pages)
                    importPanel.visibility = View.GONE
                    importing = false
                    Toast.makeText(this, "Imported $count pages", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                ui.post {
                    importPanel.visibility = View.GONE
                    importing = false
                    Toast.makeText(this, e.message ?: "Could not import that PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Writes the whole notebook (backgrounds + ink) to Downloads/Ink Notes as a PDF. */
    private fun exportPdf() {
        if (importing) return
        importing = true
        hideSettings()
        save()
        importText.text = "Exporting PDF..."
        importPanel.visibility = View.VISIBLE
        thread(name = "pdf-export") {
            try {
                val r = PdfExporter.export(this, note, pdfs) { i, n -> ui.post { importText.text = "Exporting PDF...\n\npage $i of $n" } }
                ui.post {
                    importPanel.visibility = View.GONE; importing = false
                    val mb = r.bytes / 1048576.0
                    Toast.makeText(this, "Saved ${r.pages} pages to Download/Ink Notes/${r.name} (${"%.1f".format(mb)} MB)", Toast.LENGTH_LONG).show()
                    if (debuggableApp) Log.d("InkDebug", "exported ${r.uri} ${r.pages} pages ${r.bytes} bytes name=${r.name}")
                }
            } catch (e: Throwable) {
                ui.post {
                    importPanel.visibility = View.GONE; importing = false
                    Toast.makeText(this, "Could not export: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmDeletePage() {
        AlertDialog.Builder(this)
            .setMessage("Delete page ${ink.pageIndex + 1} of ${note.pages.size}? Its writing goes with it.")
            .setPositiveButton("Delete") { _, _ -> ink.deleteCurrentPage(); refreshChrome(); hideSettings() }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ---- settings ----------------------------------------------------------------------------

    private fun buildSettings(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
            setPadding(40, 30, 40, 30)
        }
        p.addView(TextView(this).apply { text = "Settings"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.BLACK) })
        inkModeBtn = tb("") {
            presetIndex = (presetIndex + 1) % presets.size
            prefs.edit().putInt("preset", presetIndex).apply()
            if (presetIndex == 0) eink.restore()
            refreshChrome()
        }
        polishBtn = tb("") { polish = !polish; prefs.edit().putBoolean("polish", polish).apply(); refreshChrome() }
        paperBtn = tb("") { ink.setPaper(ink.page.paper.next()); refreshChrome() }
        sideBtn = tb("") {
            val order = listOf(Tool.ERASER, Tool.SELECT, Tool.HAND, Tool.HIGHLIGHTER, Tool.PEN)
            ink.sideButtonTool = order[(order.indexOf(ink.sideButtonTool) + 1) % order.size]
            prefs.edit().putInt("side", ink.sideButtonTool.ordinal).apply()
            refreshChrome()
        }
        snapBtn = tb("") { ink.settings.shapeSnap = !ink.settings.shapeSnap; saveToolSettings(); refreshChrome() }
        for (b in listOf(inkModeBtn, polishBtn, snapBtn, paperBtn, sideBtn,
            tb("Back to library") { save(); finish() },
            tb("Import PDF into this notebook...") { pickPdf() },
            tb("Delete this page") { confirmDeletePage() },
            tb("Export notebook as PDF") { exportPdf() },
            tb("Full screen refresh now") { eink.fullRefresh(); hideSettings() },
            tb("Open device test tools (Probe)") { save(); startActivity(Intent(this, ProbeActivity::class.java)) },
            tb("Android 'About' screen (tap Build number 7 times)") { openAbout() },
            tb("Developer options (after unlocking)") { openDeveloperOptions() },
            tb("Close") { hideSettings() })) {
            p.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 })
        }
        devStatus = TextView(this).apply { textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 22, 0, 0) }
        p.addView(devStatus)
        p.addView(TextView(this).apply {
            text = "Buttons: Volume Up/Down turn pages. In the tablet's own settings you can also give buttons " +
                "Zoom In/Out, arrow keys, Page Up/Down, Tab (next tool) or Enter (new page); they work here."
            textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 24, 0, 0)
        })
        return p
    }

    private fun showSettings() {
        refreshChrome()
        updateDevStatus()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun updateDevStatus() {
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        devStatus.text = "Developer options: " + (if (dev) "ON" else "off") + "   USB debugging: " + (if (adb) "ON" else "off")
    }

    private fun hideSettings() { settingsPanel.visibility = View.GONE; ink.repaintAll() }

    private fun showPages() { pagesPanel.show(); pagesPanel.visibility = View.VISIBLE }
    private fun hidePages() { pagesPanel.visibility = View.GONE; refreshChrome(); ink.repaintAll() }

    /** Starts the first of these screens that exists on this firmware. */
    private fun startFirst(vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                if (i.resolveActivity(packageManager) != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return true
                }
            } catch (_: Throwable) { }
        }
        return false
    }

    private fun openAbout() {
        val ok = startFirst(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$MyDeviceInfoActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DeviceInfoSettingsActivity"),
            Intent(Settings.ACTION_SETTINGS)
        )
        if (!ok) Toast.makeText(this, "No About screen found on this firmware", Toast.LENGTH_LONG).show()
    }

    private fun openDeveloperOptions() {
        val ok = startFirst(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings")
        )
        if (!ok) Toast.makeText(this, "Developer options are still locked: unlock them from the About screen first", Toast.LENGTH_LONG).show()
    }

    // ---- InkView.Host ------------------------------------------------------------------------

    override fun onPenDown() {
        ui.removeCallbacks(settleToQuality)
        val fast = presets[presetIndex].second ?: return
        if (!inFast) { inFast = true; eink.set(fast, "pen down") }
    }

    override fun onPenUp() {
        if (presets[presetIndex].second == null) return
        ui.removeCallbacks(settleToQuality)
        ui.postDelayed(settleToQuality, 700)
    }

    override fun onDocumentChanged() {
        refreshChrome()
        updateSelectionBar()
        ui.removeCallbacks(saveNow)
        ui.postDelayed(saveNow, 2000)
    }

    // ---- keys --------------------------------------------------------------------------------

    private val toolCycle = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.SELECT, Tool.SHAPE, Tool.HAND)

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        if (settingsPanel.visibility == View.VISIBLE && code == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) hideSettings()
            return true
        }
        if (pagesPanel.visibility == View.VISIBLE && code == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) hidePages()
            return true
        }
        val handled = when (code) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_ZOOM_OUT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER,
            297, KeyEvent.KEYCODE_UNKNOWN -> true            // 297 / unknown: the pen's side-button key events
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(ev)
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) && ev.action == KeyEvent.ACTION_DOWN) {
            ink.ignoreVirtualFor(1500)      // the firmware follows a volume press with a fake swipe
        }
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && settingsPanel.visibility != View.VISIBLE) {
            val zoomed = ink.zoomIndex > 0
            when (code) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> goPage(-1)
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> goPage(1)
                KeyEvent.KEYCODE_ZOOM_IN -> ink.zoomBy(1)
                KeyEvent.KEYCODE_ZOOM_OUT -> ink.zoomBy(-1)
                KeyEvent.KEYCODE_DPAD_UP -> ink.panBy(0f, -0.4f)
                KeyEvent.KEYCODE_DPAD_DOWN -> ink.panBy(0f, 0.4f)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (zoomed) ink.panBy(-0.4f, 0f) else goPage(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (zoomed) ink.panBy(0.4f, 0f) else goPage(1)
                KeyEvent.KEYCODE_TAB -> selectTool(toolCycle[(toolCycle.indexOf(ink.tool) + 1) % toolCycle.size])
                KeyEvent.KEYCODE_ENTER -> ink.addPageAfterCurrent()
            }
        }
        return true
    }

    // ---- lifecycle / saving ------------------------------------------------------------------

    /**
     * Keeps the notebook as it was at the start of the last two sessions, so a mistake (or a bug) can never
     * cost more than one session of writing.
     */
    private fun rotateBackups() {
        try {
            if (!file.exists()) return
            val dir = file.parentFile ?: return
            val prev = File(dir, "$notebookName.session-1.bak")
            val prev2 = File(dir, "$notebookName.session-2.bak")
            if (prev.exists()) prev.copyTo(prev2, overwrite = true)
            file.copyTo(prev, overwrite = true)
        } catch (_: Throwable) { }
    }

    private fun save() {
        prefs.edit().putInt("lastPage_$notebookName", ink.pageIndex).apply()
        try { NoteStore.save(note, file) } catch (t: Throwable) {
            Toast.makeText(this, "Could not save: $t", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::devStatus.isInitialized && settingsPanel.visibility == View.VISIBLE) updateDevStatus()
    }

    // The panel mode is system-wide, so always hand it back when we leave.
    override fun onPause() {
        ui.removeCallbacks(settleToQuality); ui.removeCallbacks(saveNow)
        inFast = false
        eink.restoreNow()
        save()
        try { Thumbnailer.save(note.pages[0], pdfs, 360, File(file.parentFile, "$notebookName.png")) } catch (_: Throwable) { }
        super.onPause()
    }

    override fun onDestroy() {
        eink.restoreNow()
        super.onDestroy()
    }

    /**
     * Debug builds only (never in a release build): lets a developer drive the app over USB with
     *   adb shell am broadcast -a app.einknotes.DEBUG --es cmd <command>
     * Results go to logcat under the tag InkDebug.
     */
    private fun registerDebugControl() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: return
                when (cmd) {
                    "tool_pen" -> selectTool(Tool.PEN)
                    "tool_marker" -> selectTool(Tool.HIGHLIGHTER)
                    "tool_calli" -> { selectTool(Tool.PEN); ink.tool = Tool.CALLIGRAPHY }
                    "tool_shape" -> selectTool(Tool.SHAPE)
                    "shape_kind" -> UserShape.values().firstOrNull { it.name == i.getStringExtra("v") }?.let { ink.settings.shapeTool = it }
                    "snap" -> { ink.settings.shapeSnap = i.getBooleanExtra("v", true); saveToolSettings() }
                    "tool_erase" -> selectTool(Tool.ERASER)
                    "tool_select" -> selectTool(Tool.SELECT)
                    "tool_hand" -> selectTool(Tool.HAND)
                    "undo" -> ink.undo()
                    "redo" -> ink.redo()
                    "page_next" -> goPage(1)
                    "page_prev" -> goPage(-1)
                    "zoom_in" -> ink.zoomBy(1)
                    "zoom_out" -> ink.zoomBy(-1)
                    "add_page" -> ink.addPageAfterCurrent()
                    "save" -> save()
                    "select_all" -> ink.selectAll()
                    "delete_sel" -> ink.deleteSelection()
                    "copy_sel" -> { Clipboard.items = ink.copySelection(); updateSelectionBar() }
                    "paste" -> ink.paste(Clipboard.items)
                    "dup_sel" -> ink.duplicateSelection()
                    "clear_sel" -> ink.clearSelection()
                    "import_path" -> i.getStringExtra("path")?.let { importPdf(Uri.fromFile(File(it))) }
                    "delete_page" -> { ink.deleteCurrentPage(); refreshChrome() }
                    "export_pdf" -> exportPdf()
                    "pages_open" -> showPages()
                    "pages_close" -> hidePages()
                    "goto" -> { ink.goToPage(i.getIntExtra("v", 1) - 1); refreshChrome() }
                    "restore_backup" -> {
                        val b = NoteStore.load(File(filesDir, "notes/notebook1.v2-backup.inkn"))
                        ink.insertPagesAt(0, b.pages); refreshChrome()
                    }
                    "gray" -> { ink.settings.penGray = i.getIntExtra("v", 0); ink.settings.hlGray = i.getIntExtra("h", ink.settings.hlGray) }
                }
                Log.d("InkDebug", "cmd=$cmd -> ${ink.describe()} einkMode=${EinkController.nameOf(eink.readMode())}")
            }
        }, IntentFilter("app.einknotes.DEBUG"))
    }
}
