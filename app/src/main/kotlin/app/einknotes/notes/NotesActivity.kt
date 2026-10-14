package app.einknotes.notes

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.EinkController
import app.einknotes.ProbeActivity
import app.einknotes.notes.ai.AiAssistant
import app.einknotes.notes.ai.StudyPanel
import app.einknotes.notes.ai.StudySet
import app.einknotes.notes.ai.Card
import app.einknotes.notes.ai.Question
import app.einknotes.notes.ai.QuizKind
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Notice
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.Well
import app.einknotes.notes.ui.divider
import app.einknotes.notes.ui.label
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** In-app clipboard for items (works across pages and notebooks). */
object Clipboard {
    var items: List<Item> = emptyList()
}

/**
 * The notebook editor. Pen-only tablet, so everything is reachable with the pen; the hardware buttons add shortcuts.
 *
 * Layout (Notability-like, drawn for e-ink): a top bar (library, title, page, undo/redo, record, more), a tool row
 * with the active tool's quick picks ("wells": widths and greys) always visible, the page, and one context bar at the
 * bottom that shows selection actions or audio playback. Menus are flat sheets, never animated dialogs.
 */
class NotesActivity : Activity(), InkView.Host {

    companion object {
        /** The editor that is open right now (at most one per notebook may live). */
        @Volatile private var live: NotesActivity? = null
        private var crashHandlerInstalled = false

        /** Notability's width scale (shown 1..12) as our width multipliers: pen 2 = our 1.0, highlighter 20 = our 1.0. */
        val WIDTH_STEPS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f, 7.5f, 10f)
        val PEN_GREYS = intArrayOf(0, 45, 90, 130, 170)
        val HL_GREYS = intArrayOf(230, 210, 190, 170, 150, 130)
    }

    /** False until the notebook loaded and the screen was built; nothing is saved before that. */
    private var ready = false
    /** True once a newer editor took over this notebook: this one must never save again. */
    private var retired = false
    /** The notebook changed since it was last written. */
    private var dirty = false
    private var debugReceiver: BroadcastReceiver? = null
    private lateinit var pdfText: PdfTextEngine
    private lateinit var studyPanel: StudyPanel
    private lateinit var assistant: AiAssistant

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

    // chrome
    private lateinit var frame: FrameLayout
    private lateinit var sheet: Sheet
    private lateinit var notice: Notice
    private lateinit var titleView: TextView
    private lateinit var pageBtn: EButton
    private lateinit var undoBtn: EButton
    private lateinit var redoBtn: EButton
    private lateinit var recBtn: EButton
    private val toolButtons = LinkedHashMap<Tool, EButton>()
    private lateinit var wellsBar: LinearLayout
    private lateinit var contextBar: FrameLayout
    private lateinit var zoomBar: LinearLayout
    private lateinit var zoomLabelBtn: EButton
    private lateinit var pagesPanel: PagesPanel

    // selection bar
    private lateinit var selectionBar: LinearLayout
    private lateinit var pasteBtn: EButton
    private lateinit var editBtn: EButton
    private lateinit var selectionButtons: List<EButton>

    // play bar
    private lateinit var playBar: LinearLayout
    private lateinit var playPauseBtn: EButton
    private lateinit var playPosBtn: EButton
    private lateinit var replayBtn: EButton
    private var replayOn = false

    // fast-ink presets: (label, mode while writing, mode afterwards)
    private val presets = listOf(
        Triple("Off", null, null),
        Triple("AutoDU, then GC16", EinkController.AUTO_DU, EinkController.PART_GC16),
        Triple("DU, then GL16", EinkController.DU, EinkController.PART_GL16),
        Triple("A2, then GL16", EinkController.A2, EinkController.PART_GL16)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eink = EinkController(this)
        if (!crashHandlerInstalled) {
            // One handler for the whole process: hand the panel mode back whichever editor is open.
            crashHandlerInstalled = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e -> try { live?.eink?.restoreNow() } catch (_: Throwable) { }; prev?.uncaughtException(t, e) }
        }

        presetIndex = prefs.getInt("preset", 1).coerceIn(0, presets.size - 1)
        polish = prefs.getBoolean("polish", false)
        if (prefs.getBoolean("keepAwake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The library says which notebook to open (adb tests use e.g. --es notebook scratch so real notes stay untouched).
        notebookName = intent?.getStringExtra("notebook")?.filter { it.isLetterOrDigit() || it == '_' }
            ?.ifEmpty { null } ?: "notebook1"
        file = File(filesDir, "notes/$notebookName.inkn")
        note = try { NoteStore.load(file) } catch (e: NoteStore.Unreadable) {
            Log.e("InkNotes", "notebook unreadable", e)
            showUnreadable()
            return
        }
        // An older editor of the same notebook (still in the back stack) would later save stale pages: retire it.
        live?.let { old -> if (old !== this && old.notebookName == notebookName) { old.retired = true; old.finish() } }
        live = this
        rotateBackups()
        getSharedPreferences("library", MODE_PRIVATE).edit().putLong("opened_$notebookName", System.currentTimeMillis()).apply()
        pdfs = PdfLibrary(this)
        ImageStore.init(this)
        ink = InkView(this, note, pdfs, this)
        pdfText = PdfTextEngine(this, pdfs)
        ink.pdfText = pdfText
        ink.sideButtonTool = Tool.values().getOrElse(prefs.getInt("side", Tool.ERASER.ordinal)) { Tool.ERASER }
        loadToolSettings()

        buildScreen()

        selectTool(Tool.PEN)
        // come back to the page you were writing on
        ink.goToPage(prefs.getInt("lastPage_$notebookName", 0).coerceIn(0, note.pages.size - 1))
        refreshChrome()
        registerDebugControl()
        handleIncoming(intent)
        ready = true
    }

    // ---- screen ------------------------------------------------------------------------------

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 4, 6, 0) }
        top.addView(icon("back", "Library") { save(); finish() }, LinearLayout.LayoutParams(130, Eink.BAR_H))
        titleView = label(note.title, 22f, bold = true).apply {
            setPadding(16, 0, 16, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { renameNotebook() }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        pageBtn = EButton(this, null, "1 / 1") { showPages() }.apply { textSize = 26f }
        top.addView(pageBtn, LinearLayout.LayoutParams(170, Eink.BAR_H - 12))
        top.addView(icon("up", null) { goPage(-1) }, LinearLayout.LayoutParams(96, Eink.BAR_H))
        top.addView(icon("down", null) { goPage(1) }, LinearLayout.LayoutParams(96, Eink.BAR_H))
        undoBtn = icon("undo", null) { ink.undo() }
        redoBtn = icon("redo", null) { ink.redo() }
        recBtn = icon("mic", "Rec") { onRecTapped() }
        top.addView(undoBtn, LinearLayout.LayoutParams(96, Eink.BAR_H))
        top.addView(redoBtn, LinearLayout.LayoutParams(96, Eink.BAR_H))
        top.addView(icon("paper", "Paper") { showPaper() }, LinearLayout.LayoutParams(110, Eink.BAR_H))
        top.addView(recBtn, LinearLayout.LayoutParams(110, Eink.BAR_H))
        top.addView(icon("more", null) { showMore() }, LinearLayout.LayoutParams(96, Eink.BAR_H))
        root.addView(top)

        // tool row with the active tool's wells
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 0, 6, 4) }
        for ((tool, ic, name) in listOf(
            Triple(Tool.PEN, "pen", "Pen"), Triple(Tool.HIGHLIGHTER, "highlighter", "Marker"), Triple(Tool.ERASER, "eraser", "Eraser"),
            Triple(Tool.SELECT, "lasso", "Select"), Triple(Tool.SHAPE, "shape", "Shape"), Triple(Tool.TEXT, "text", "Text"),
            Triple(Tool.TAPE, "tape", "Tape"), Triple(Tool.HAND, "hand", "Hand"))) {
            val b = icon(ic, name) { onToolTapped(tool) }
            toolButtons[tool] = b
            tools.addView(b, LinearLayout.LayoutParams(100, 112))
        }
        tools.addView(divider(), LinearLayout.LayoutParams(3, 80).apply { leftMargin = 8; rightMargin = 8 })
        wellsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tools.addView(wellsBar, LinearLayout.LayoutParams(0, 112, 1f))
        root.addView(tools)
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 3))

        // the page, with one context bar on top so showing it never resizes (and re-renders) the page
        val pageArea = FrameLayout(this)
        pageArea.addView(ink, FrameLayout.LayoutParams(-1, -1))
        contextBar = FrameLayout(this).apply { setBackgroundColor(Color.WHITE); visibility = View.GONE }
        pageArea.addView(contextBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        selectionBar = buildSelectionBar()
        playBar = buildPlayBar()
        zoomBar = buildZoomBar()
        root.addView(pageArea, LinearLayout.LayoutParams(-1, 0, 1f))

        frame = FrameLayout(this)
        frame.addView(root)
        sheet = Sheet(this, frame)
        notice = Notice(frame)
        pagesPanel = PagesPanel(this, note, pdfs, ink, sheet, { hidePages() }, { i -> ink.goToPage(i); hidePages(); refreshChrome() })
        pagesPanel.visibility = View.GONE
        frame.addView(pagesPanel)
        studyPanel = StudyPanel(this) { hideStudy() }
        studyPanel.visibility = View.GONE
        frame.addView(studyPanel)
        importPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true
            setPadding(60, 400, 60, 60); visibility = View.GONE
        }
        importText = label("", 22f)
        importPanel.addView(importText)
        frame.addView(importPanel)
        setContentView(frame)
        assistant = AiAssistant(this, note, notebookName, pdfs, pdfText, ink, sheet, ui, { say(it) }, { setWork(it) },
            { r, ms -> startPlayback(r, ms) }, { set, quiz -> showStudy(set, quiz) }, { })
    }

    /** Shows (or hides, with null) the full-screen "working" panel and blocks the keys meanwhile. */
    private fun setWork(text: String?) {
        if (text == null) { importPanel.visibility = View.GONE; importing = false }
        else { importing = true; importText.text = text; importPanel.visibility = View.VISIBLE }
    }

    private fun showStudy(set: StudySet, quiz: Boolean) {
        studyPanel.visibility = View.VISIBLE
        if (quiz) studyPanel.startQuiz(set) else studyPanel.startFlashcards(set) { assistant.saveStudy() }
    }

    private fun hideStudy() { studyPanel.visibility = View.GONE; ink.repaintAll() }

    private fun icon(name: String, text: String?, onClick: () -> Unit) =
        EButton(this, name, text) { onClick() }.apply { bordered = false }

    private fun say(text: String) { if (::notice.isInitialized) notice.show(text) }

    /** Shows [bar] in the context slot at the bottom of the page (only one at a time), or hides the slot. */
    private fun showContext(bar: View?) {
        contextBar.removeAllViews()
        if (bar == null) { contextBar.visibility = View.GONE; return }
        (bar.parent as? android.view.ViewGroup)?.removeView(bar)
        contextBar.addView(bar, FrameLayout.LayoutParams(-1, -2))
        contextBar.visibility = View.VISIBLE
    }

    private fun updateContext() {
        when {
            AudioNotes.playing != null -> showContext(playBar)
            ink.tool == Tool.SELECT && (ink.hasSelection || Clipboard.items.isNotEmpty()) -> showContext(selectionBar)
            ink.zoomed -> showContext(zoomBar)
            else -> showContext(null)
        }
    }

    /**
     * The notebook file exists but cannot be read. Nothing is saved over it; the person can put back one of the
     * automatic backups (the damaged file is kept next to it).
     */
    private fun showUnreadable() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(48, 120, 48, 48) }
        box.addView(label("This notebook could not be opened.\n\nIt has not been changed. You can put back an automatic backup below " +
            "(the damaged copy is kept), or go back to the library.", 20f))
        val dir = file.parentFile!!
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        val candidates = listOf("session-1.bak", "session-2.bak", "pre-library.bak", "v2-backup.inkn")
            .map { File(dir, "$notebookName.$it") }
            .filter { f -> f.exists() && try { NoteStore.load(f); true } catch (_: Throwable) { false } }
        for (f in candidates) {
            box.addView(EButton(this, null, "Restore backup from ${fmt.format(java.util.Date(f.lastModified()))}") {
                try {
                    file.copyTo(File(dir, "$notebookName.damaged-${System.currentTimeMillis()}"), overwrite = false)
                    f.copyTo(file, overwrite = true)
                    recreate()
                } catch (t: Throwable) { box.addView(label("Could not restore: ${t.message}", 18f)) }
            }, LinearLayout.LayoutParams(-1, Eink.BAR_H).apply { topMargin = 24 })
        }
        if (candidates.isEmpty()) box.addView(label("\nNo readable backup was found.", 18f))
        box.addView(EButton(this, null, "Back to library") { finish() }, LinearLayout.LayoutParams(-1, Eink.BAR_H).apply { topMargin = 24 })
        setContentView(box)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun renameNotebook() {
        sheet.input("Notebook name", note.title) { t ->
            val name = t.trim().ifEmpty { return@input }
            note.title = name; titleView.text = name; onDocumentChanged()
        }
    }

    // ---- tools and wells ---------------------------------------------------------------------

    private fun onToolTapped(t: Tool) {
        val current = if (ink.tool == Tool.CALLIGRAPHY) Tool.PEN else ink.tool
        if (current == t && t != Tool.HAND) showToolOptions() else selectTool(t)
    }

    private fun selectTool(t: Tool) {
        if (t != Tool.SELECT) ink.clearSelection()
        ink.tool = t
        for ((tool, b) in toolButtons) b.selectedState = tool == t || (tool == Tool.PEN && t == Tool.CALLIGRAPHY)
        toolButtons[Tool.PEN]?.let { it.icon = if (t == Tool.CALLIGRAPHY) "calligraphy" else "pen" }
        buildWells()
        updateContext()
        updateSelectionBar()
    }

    /** Remembered quick picks per tool (editable with a long press). */
    private fun wells(key: String, def: List<Float>): MutableList<Float> =
        (prefs.getString("wells_$key", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == def.size } ?: def).toMutableList()

    private fun saveWells(key: String, v: List<Float>) { prefs.edit().putString("wells_$key", v.joinToString(",")).apply() }

    private fun buildWells() {
        wellsBar.removeAllViews()
        val s = ink.settings
        fun widthWells(key: String, def: List<Float>, get: () -> Float, set: (Float) -> Unit, choices: FloatArray, scaleFor: (Float) -> Float) {
            val values = wells(key, def)
            for ((i, v) in values.withIndex()) {
                val w = Well(this, Well.Kind.WIDTH, v) { set(it.value); saveToolSettings(); buildWells() }
                w.current = Math.abs(get() - v) < 0.01f
                w.onLongPress = { _ ->
                    sheet.list("Width for this quick pick", choices.mapIndexed { n, c ->
                        val scale = scaleFor(c)
                        Row("Size ${n + 1}", detail = if (Math.abs(scale - v) < 0.01f) "current" else null, selected = Math.abs(scale - v) < 0.01f) {
                            values[i] = scale; saveWells(key, values); set(scale); saveToolSettings(); buildWells()
                        }
                    })
                }
                wellsBar.addView(w, LinearLayout.LayoutParams(66, 100))
            }
        }
        fun greyWells(key: String, def: List<Float>, get: () -> Int, set: (Int) -> Unit, choices: IntArray) {
            val values = wells(key, def)
            for ((i, v) in values.withIndex()) {
                val w = Well(this, Well.Kind.GREY, v) { set(it.value.toInt()); saveToolSettings(); buildWells() }
                w.current = get() == v.toInt()
                w.onLongPress = { _ ->
                    sheet.list("Grey for this quick pick", choices.map { g ->
                        Row(greyName(g), selected = g == v.toInt()) { values[i] = g.toFloat(); saveWells(key, values); set(g); saveToolSettings(); buildWells() }
                    })
                }
                wellsBar.addView(w, LinearLayout.LayoutParams(66, 100))
            }
        }
        fun gap() = wellsBar.addView(View(this), LinearLayout.LayoutParams(16, 1))
        fun small(ic: String?, text: String?, selected: Boolean = false, onClick: () -> Unit) =
            wellsBar.addView(EButton(this, ic, text) { onClick() }.apply { textSize = 24f; selectedState = selected }, LinearLayout.LayoutParams(-2, 92).apply { leftMargin = 6 })
        val penScale = { n: Float -> n / 2f }
        when (ink.tool) {
            Tool.PEN -> {
                widthWells("pen_w", listOf(0.5f, 1f, 1.5f), { s.penWidth }, { s.penWidth = it }, WIDTH_STEPS, penScale); gap()
                greyWells("pen_g", listOf(0f, 70f, 140f), { s.penGray }, { s.penGray = it }, PEN_GREYS)
            }
            Tool.CALLIGRAPHY -> {
                widthWells("cal_w", listOf(0.5f, 1f, 1.5f), { s.calWidth }, { s.calWidth = it }, WIDTH_STEPS, penScale); gap()
                greyWells("cal_g", listOf(0f, 70f, 140f), { s.calGray }, { s.calGray = it }, PEN_GREYS)
            }
            Tool.HIGHLIGHTER -> {
                widthWells("hl_w", listOf(0.5f, 0.75f, 1f), { s.hlWidth }, { s.hlWidth = it }, floatArrayOf(5f, 7.5f, 10f, 15f, 20f, 30f, 40f)) { it / 20f }; gap()
                greyWells("hl_g", listOf(210f, 170f, 130f), { s.hlGray }, { s.hlGray = it }, HL_GREYS)
            }
            Tool.ERASER -> {
                val sizes = wells("er_w", listOf(10f, 18f, 32f))
                for (v in sizes) wellsBar.addView(Well(this, Well.Kind.WIDTH, v / 6f) { s.eraserRadius = v; saveToolSettings(); buildWells() }
                    .apply { current = Math.abs(s.eraserRadius - v) < 0.5f }, LinearLayout.LayoutParams(66, 100))
                gap()
                small(null, "Whole", !s.eraserPartial) { s.eraserPartial = false; saveToolSettings(); buildWells() }
                small(null, "Partial", s.eraserPartial) { s.eraserPartial = true; saveToolSettings(); buildWells() }
            }
            Tool.SHAPE -> {
                small(null, s.shapeTool.label) { chooseShape() }
                widthWells("sh_w", listOf(0.5f, 1f, 1.5f), { s.penWidth }, { s.penWidth = it }, WIDTH_STEPS, penScale); gap()
                greyWells("pen_g", listOf(0f, 70f, 140f), { s.penGray }, { s.penGray = it }, PEN_GREYS)
            }
            Tool.TEXT -> {
                for (v in wells("tx_s", listOf(28f, 38f, 56f))) wellsBar.addView(Well(this, Well.Kind.TEXT, v) { s.textSize = v; saveToolSettings(); buildWells() }
                    .apply { current = Math.abs(s.textSize - v) < 0.5f }, LinearLayout.LayoutParams(66, 100))
                gap()
                greyWells("tx_g", listOf(0f, 70f, 140f), { s.textGray }, { s.textGray = it }, PEN_GREYS)
                small(null, "B", s.textBold) { s.textBold = !s.textBold; saveToolSettings(); buildWells() }
            }
            Tool.TAPE -> {
                small(null, "Hide all") { ink.setAllTape(true) }
                small(null, "Show all") { ink.setAllTape(false) }
            }
            Tool.SELECT -> {
                small(null, "Select all") { ink.selectAll() }
            }
            Tool.HAND -> {
                small(null, "Whole page") { ink.zoomOut() }
                small(null, "Page width") { ink.zoomToWidth() }
                small(null, "Zoom " + ink.zoomLabel()) { ink.cycleZoom() }
            }
        }
        if (ink.tool != Tool.HAND && ink.tool != Tool.SELECT && ink.tool != Tool.TAPE) {
            wellsBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            wellsBar.addView(icon("gear", null) { showToolOptions() }, LinearLayout.LayoutParams(84, 100))
        }
    }

    private fun greyName(g: Int) = when {
        g <= 10 -> "Black"; g <= 60 -> "Very dark"; g <= 100 -> "Dark"; g <= 140 -> "Mid"; g <= 180 -> "Light"; g <= 215 -> "Pale"; else -> "Very pale"
    }

    private fun chooseShape() {
        val kinds = UserShape.values().toList()
        sheet.choose("Shape", kinds.map { it.label to it }, ink.settings.shapeTool) { ink.settings.shapeTool = it; saveToolSettings(); buildWells() }
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** The less frequent options of the active tool. */
    private fun showToolOptions() {
        val s = ink.settings
        val rows = ArrayList<Row>()
        val reopen = { showToolOptions() }
        when (ink.tool) {
            Tool.PEN, Tool.CALLIGRAPHY -> {
                rows.add(Row("Style", if (ink.tool == Tool.CALLIGRAPHY) "Calligraphy (width follows the nib angle)" else "Pen (width follows pressure)") {
                    ink.tool = if (ink.tool == Tool.CALLIGRAPHY) Tool.PEN else Tool.CALLIGRAPHY; selectTool(ink.tool); reopen()
                })
                if (ink.tool == Tool.CALLIGRAPHY) {
                    val angles = listOf("0°" to 0f, "45°" to 0.7854f, "90°" to 1.5708f, "135°" to 2.3562f)
                    rows.add(Row("Nib angle", angles.minByOrNull { Math.abs(it.second - s.calAngle) }!!.first) {
                        sheet.choose("Nib angle", angles, angles.minByOrNull { Math.abs(it.second - s.calAngle) }!!.second) { s.calAngle = it; saveToolSettings() }
                    })
                    val flats = listOf("Soft" to 0.5f, "Medium" to 0.75f, "Broad" to 0.95f)
                    rows.add(Row("Nib", flats.minByOrNull { Math.abs(it.second - s.calFlat) }!!.first) {
                        sheet.choose("Nib", flats, flats.minByOrNull { Math.abs(it.second - s.calFlat) }!!.second) { s.calFlat = it; saveToolSettings() }
                    })
                }
                rows.add(Row("Smoothing", listOf("Off", "Light", "Strong")[s.smoothing.coerceIn(0, 2)]) {
                    sheet.choose("Smoothing", listOf("Off" to 0, "Light" to 1, "Strong" to 2), s.smoothing) { s.smoothing = it; saveToolSettings() }
                })
                rows.add(Row("Ruler", onOff(ink.rulerOn) + " - a straight edge you can move and turn") { ink.rulerOn = !ink.rulerOn })
                rows.add(Row("Hold pen still to snap shapes", onOff(s.shapeSnap)) { s.shapeSnap = !s.shapeSnap; saveToolSettings() })
            }
            Tool.HIGHLIGHTER -> {
                rows.add(Row("Snap to the text of PDF pages", onOff(s.textSnap) + " - drag along words and they are highlighted line by line") { s.textSnap = !s.textSnap; saveToolSettings(); reopen() })
                rows.add(Row("Ruler", onOff(ink.rulerOn)) { ink.rulerOn = !ink.rulerOn })
            }
            Tool.ERASER -> {
                rows.add(Row("Mode", if (s.eraserPartial) "Partial: erase only the area touched" else "Whole: erase whole strokes") { s.eraserPartial = !s.eraserPartial; saveToolSettings(); buildWells() })
            }
            Tool.SHAPE -> rows.add(Row("Shape", s.shapeTool.label) { chooseShape() })
            Tool.TEXT -> rows.add(Row("Bold", onOff(s.textBold)) { s.textBold = !s.textBold; saveToolSettings(); buildWells() })
            else -> return
        }
        rows.add(Row("Quick picks", "Long-press a width or grey above to change it", enabled = false) { })
        sheet.list("Tool options", rows)
    }

    private fun loadToolSettings() {
        val s = ink.settings; val p = prefs
        s.penWidth = p.getFloat("penWidth", s.penWidth); s.penGray = p.getInt("penGray", s.penGray)
        s.hlWidth = p.getFloat("hlWidth", s.hlWidth); s.hlGray = p.getInt("hlGray", s.hlGray)
        s.calWidth = p.getFloat("calWidth", s.calWidth); s.calGray = p.getInt("calGray", s.calGray)
        s.calAngle = p.getFloat("calAngle", s.calAngle); s.calFlat = p.getFloat("calFlat", s.calFlat)
        s.shapeSnap = p.getBoolean("shapeSnap", true)
        s.eraserPartial = p.getBoolean("eraserPartial", false); s.eraserRadius = p.getFloat("eraserRadius", 18f)
        s.textSize = p.getFloat("textSize", 38f); s.textGray = p.getInt("textGray", 0); s.textBold = p.getBoolean("textBold", false)
        s.smoothing = p.getInt("smoothing", 0)
        s.zoomTaps = p.getInt("zoomTaps", 3); s.zoomTarget = p.getFloat("zoomTarget", 3f); s.zoomSquares = p.getBoolean("zoomSquares", false); s.autoAdvance = p.getBoolean("autoAdvance", true); s.textSnap = p.getBoolean("textSnap", true)
        s.shapeTool = UserShape.values().getOrElse(p.getInt("shapeTool", UserShape.RECT.ordinal)) { UserShape.RECT }
    }

    private fun saveToolSettings() {
        val s = ink.settings
        prefs.edit().putFloat("penWidth", s.penWidth).putInt("penGray", s.penGray).putFloat("hlWidth", s.hlWidth)
            .putInt("hlGray", s.hlGray).putFloat("calWidth", s.calWidth).putInt("calGray", s.calGray)
            .putFloat("calAngle", s.calAngle).putFloat("calFlat", s.calFlat)
            .putBoolean("eraserPartial", s.eraserPartial).putFloat("eraserRadius", s.eraserRadius)
            .putFloat("textSize", s.textSize).putInt("textGray", s.textGray).putBoolean("textBold", s.textBold)
            .putInt("smoothing", s.smoothing)
            .putInt("zoomTaps", s.zoomTaps).putFloat("zoomTarget", s.zoomTarget).putBoolean("zoomSquares", s.zoomSquares).putBoolean("autoAdvance", s.autoAdvance).putBoolean("textSnap", s.textSnap)
            .putBoolean("shapeSnap", s.shapeSnap).putInt("shapeTool", s.shapeTool.ordinal).apply()
    }

    // ---- selection bar -----------------------------------------------------------------------

    private fun bar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(8, 10, 8, 10)
    }

    private fun barButton(text: String, onClick: () -> Unit) = EButton(this, null, text) { onClick() }.apply { textSize = 26f }

    private fun buildSelectionBar(): LinearLayout {
        val b = bar()
        val del = barButton("Delete") { ink.deleteSelection() }
        val copy = barButton("Copy") { Clipboard.items = ink.copySelection(); updateSelectionBar() }
        val cut = barButton("Cut") { Clipboard.items = ink.copySelection(); ink.deleteSelection(); updateSelectionBar() }
        val dup = barButton("Duplicate") { ink.duplicateSelection() }
        pasteBtn = barButton("Paste") { ink.paste(Clipboard.items) }
        val done = barButton("Done") { ink.clearSelection() }
        editBtn = barButton("Edit text") { editSelectedText() }
        selectionButtons = listOf(del, copy, cut, dup, done)
        for (v in listOf(del, copy, cut, dup, pasteBtn, editBtn, done)) b.addView(v, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 6 })
        return b
    }

    /** Shown while zoomed in: move around the page, change the zoom, go back to the whole page. */
    private fun buildZoomBar(): LinearLayout {
        val b = bar()
        zoomLabelBtn = EButton(this, null, "3x") { ink.cycleZoom() }.apply { textSize = 26f }
        val items = listOf<View>(
            EButton(this, "left", null) { ink.panBy(-0.6f, 0f) }, EButton(this, "up", null) { ink.panBy(0f, -0.6f) },
            EButton(this, "down", null) { ink.panBy(0f, 0.6f) }, EButton(this, "right", null) { ink.panBy(0.6f, 0f) },
            zoomLabelBtn, barButton("Page width") { ink.zoomToWidth() }, barButton("Whole page") { ink.zoomOut() })
        for (v in items) b.addView(v, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 6 })
        return b
    }

    override fun onViewChanged() {
        if (!::contextBar.isInitialized) return
        zoomLabelBtn.label = ink.zoomLabel()
        updateContext()
        if (ink.tool == Tool.HAND) buildWells()
    }

    private fun updateSelectionBar() {
        if (!::pasteBtn.isInitialized) return
        val has = ink.hasSelection
        for (b in selectionButtons) b.enabledState = has
        pasteBtn.enabledState = Clipboard.items.isNotEmpty()
        editBtn.enabledState = ink.selectedText() != null
    }

    override fun onSelectionChanged() { updateSelectionBar(); updateContext() }

    override fun onTextRequested(x: Float, y: Float, existing: TextItem?) {
        sheet.input(if (existing != null) "Edit text" else "New text", existing?.text ?: "", multiline = true) { text ->
            if (existing != null) ink.updateText(existing, text) else ink.addText(text, x, y)
            selectTool(Tool.SELECT)          // the new text box stays selected, ready to move or resize
        }
    }

    private fun editSelectedText() {
        val t = ink.selectedText() ?: return
        sheet.input("Edit text", t.text, multiline = true) { ink.updateText(t, it) }
    }

    // ---- audio -------------------------------------------------------------------------------

    private val REQ_MIC = 43
    private val recTick = object : Runnable {
        override fun run() { if (AudioNotes.active != null) { updateRecButton(); ui.postDelayed(this, 10000) } }
    }
    private val playTick = object : Runnable {
        override fun run() {
            val r = AudioNotes.playing ?: return
            updatePlayUi()
            ink.setReplay(if (replayOn) r.id else 0, AudioNotes.positionMs())
            ui.postDelayed(this, 2000)
        }
    }

    private fun onRecTapped() {
        if (AudioNotes.active != null) { stopRecording(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else say("Recording needs the microphone permission")
        }
    }

    private fun startRecording() {
        stopPlaybackUi()
        try {
            val rec = AudioNotes.start(this, note)
            ink.recId = rec.id; ink.recStart = AudioNotes.startedElapsed()
            dirty = true; save()
            updateRecButton()
            ui.removeCallbacks(recTick); ui.postDelayed(recTick, 10000)
        } catch (e: Throwable) {
            say("Could not start recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        ui.removeCallbacks(recTick)
        AudioNotes.stopRecording(this, note)
        ink.recId = 0
        updateRecButton()
        dirty = true; save()
    }

    private fun updateRecButton() {
        val on = AudioNotes.active != null
        recBtn.icon = if (on) "stop" else "mic"
        recBtn.label = if (on) AudioNotes.clock(AudioNotes.elapsedMs()) else "Rec"
        recBtn.selectedState = on
    }

    private fun showRecordings() {
        if (note.recordings.isEmpty()) {
            say("No recordings yet. Tap Rec to start one; what you write meanwhile is linked to the sound.")
            return
        }
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        sheet.list("Audio recordings", note.recordings.map { r ->
            Row("Recording ${r.id}", "${AudioNotes.clock(r.durationMs)}  ·  ${fmt.format(java.util.Date(r.startedAt))}") { recordingMenu(r) }
        })
    }

    private fun recordingMenu(r: Recording) {
        sheet.list("Recording ${r.id}", listOf(
            Row("Play") { startPlayback(r, 0) },
            Row("Delete recording", "Your writing stays") {
                sheet.confirm("Delete recording ${r.id}?", "The sound is removed; your writing stays.", "Delete") {
                    AudioNotes.delete(this, note, r); assistant.dropTranscript(r.id); stopPlaybackUi(); dirty = true; save()
                }
            }
        ))
    }

    private fun buildPlayBar(): LinearLayout {
        val b = bar()
        playPauseBtn = EButton(this, "pause", null) { if (AudioNotes.isPlaying) AudioNotes.pause() else AudioNotes.resume(); updatePlayUi() }
        playPosBtn = EButton(this, null, "0:00") { }.apply { bordered = false; textSize = 26f }
        replayBtn = barButton("Replay: off") { replayOn = !replayOn; updatePlayUi(); AudioNotes.playing?.let { ink.setReplay(if (replayOn) it.id else 0, AudioNotes.positionMs()) } }
        for (v in listOf(playPauseBtn, barButton("-10 s") { AudioNotes.seekTo(AudioNotes.positionMs() - 10000); updatePlayUi() },
            barButton("+10 s") { AudioNotes.seekTo(AudioNotes.positionMs() + 10000); updatePlayUi() }, playPosBtn, replayBtn,
            EButton(this, "stop", null) { stopPlaybackUi() })) b.addView(v, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 6 })
        return b
    }

    private fun updatePlayUi() {
        if (!::playBar.isInitialized) return
        playPauseBtn.icon = if (AudioNotes.isPlaying) "pause" else "play"
        playPosBtn.label = AudioNotes.clock(AudioNotes.positionMs()) + " / " + AudioNotes.clock(AudioNotes.durationMs())
        replayBtn.label = "Replay: " + (if (replayOn) "on" else "off")
    }

    private fun startPlayback(r: Recording, from: Int) {
        if (AudioNotes.active != null) { say("Stop recording first"); return }
        if (!AudioNotes.play(this, r, from) { stopPlaybackUi() }) { say("Could not play that recording"); return }
        ink.onSeekItem = { item -> if (item.recId == r.id) { AudioNotes.seekTo(item.recMs); updatePlayUi() } }
        updatePlayUi()
        updateContext()
        ink.setReplay(if (replayOn) r.id else 0, from)
        ui.removeCallbacks(playTick); ui.postDelayed(playTick, 2000)
    }

    private fun stopPlaybackUi() {
        AudioNotes.stopPlayback()
        ui.removeCallbacks(playTick)
        if (::ink.isInitialized) { ink.onSeekItem = null; ink.setReplay(0, 0) }
        if (::contextBar.isInitialized) updateContext()
    }

    // ---- images ------------------------------------------------------------------------------

    private val REQ_IMAGE = 42

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
        try { startActivityForResult(i, REQ_IMAGE) } catch (_: Throwable) { say("No file picker found on this tablet") }
    }

    private fun importImage(uri: Uri) {
        thread(name = "image-import") {
            try {
                val img = ImageStore.importFrom(this, uri)
                ui.post { ink.insertImage(img); selectTool(Tool.SELECT) }
            } catch (e: Throwable) {
                ui.post { say(e.message ?: "Could not use that picture") }
            }
        }
    }

    private fun refreshChrome() {
        if (!::pageBtn.isInitialized) return
        pageBtn.label = "${ink.pageIndex + 1} / ${note.pages.size}"
        undoBtn.enabledState = ink.canUndo
        redoBtn.enabledState = ink.canRedo
        if (ink.tool == Tool.HAND) buildWells()
    }

    private fun goPage(delta: Int) {
        val n = ink.pageIndex + delta
        if (n in note.pages.indices) { ink.goToPage(n); refreshChrome() }
    }

    // ---- PDF import --------------------------------------------------------------------------

    private val REQ_PDF = 41

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf" }
        try { startActivityForResult(i, REQ_PDF) } catch (_: Throwable) { say("No file picker found on this tablet") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PDF && resultCode == RESULT_OK) data?.data?.let { importPdf(it) }
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) data?.data?.let { importImage(it) }
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
        sheet.hide()
        importText.text = "Importing PDF..."
        importPanel.visibility = View.VISIBLE
        thread(name = "pdf-import") {
            try {
                val name = pdfs.importFrom(this, uri)
                val pages = pdfs.buildPages(name) { i, n -> ui.post { importText.text = "Importing PDF...\n\nreading page $i of $n" } }
                ui.post {
                    ink.insertPagesAfterCurrent(pages)
                    importPanel.visibility = View.GONE
                    importing = false
                    say("Imported ${pages.size} pages")
                }
            } catch (e: Throwable) {
                ui.post {
                    importPanel.visibility = View.GONE
                    importing = false
                    say(e.message ?: "Could not import that PDF")
                }
            }
        }
    }

    /** Writes the whole notebook (backgrounds + ink) to Downloads/Ink Notes as a PDF. */
    private fun exportPdf() {
        if (importing) return
        importing = true
        save()
        importText.text = "Exporting PDF..."
        importPanel.visibility = View.VISIBLE
        thread(name = "pdf-export") {
            try {
                val r = PdfExporter.export(this, note, pdfs) { i, n -> ui.post { importText.text = "Exporting PDF...\n\npage $i of $n" } }
                ui.post {
                    importPanel.visibility = View.GONE; importing = false
                    val mb = r.bytes / 1048576.0
                    say("Saved ${r.pages} pages to Download/Ink Notes/${r.name} (${"%.1f".format(mb)} MB)")
                    if (debuggableApp) Log.d("InkDebug", "exported ${r.uri} ${r.pages} pages ${r.bytes} bytes name=${r.name}")
                }
            } catch (e: Throwable) {
                ui.post {
                    importPanel.visibility = View.GONE; importing = false
                    say("Could not export: ${e.message}")
                }
            }
        }
    }

    // ---- More menu and settings --------------------------------------------------------------

    private fun showMore() {
        sheet.list("Notebook", listOf(
            Row("Pages and bookmarks", "Thumbnails, reorder, copy, delete, go to a page") { showPages() },
            Row("Add a page after this one") { ink.addPageAfterCurrent() },
            Row("Paper and page size", ink.page.paper.label) { showPaper() },
            Row("Insert image...") { pickImage() },
            Row("Import PDF into this notebook...") { pickPdf() },
            Row("AI assistant", "Ask, summarize, flashcards and quiz, transcripts") { assistant.showMenu() },
            Row("Find in this notebook", "PDF text and typed text") { askFind() },
            Row("PDF outline", if (ink.page.isPdf) "Chapters of this PDF" else "Open a PDF page first", enabled = ink.page.isPdf) { showOutline() },
            Row("Audio recordings", if (note.recordings.isEmpty()) "None yet" else "${note.recordings.size}") { showRecordings() },
            Row("Export as PDF", "Saved to Download/Ink Notes") { exportPdf() },
            Row("Delete this page", "Page ${ink.pageIndex + 1} of ${note.pages.size}") { confirmDeletePage() },
            Row("Clean screen", "One full refresh to clear ghosting") { eink.fullRefresh() },
            Row("Settings") { showSettings() },
            Row("Back to library") { save(); finish() }
        ))
    }

    // ---- find and outline ----------------------------------------------------------------------------------------

    private fun pageIndexOf(pdfFile: String, pdfPage: Int) = note.pages.indexOfFirst { it.pdfFile == pdfFile && it.pdfPage == pdfPage }

    private fun askFind() {
        sheet.input("Find in this notebook", lastQuery, hint = "Word or phrase", ok = "Find") { q -> if (q.isNotBlank()) runFind(q.trim()) }
    }

    private var lastQuery = ""

    private fun runFind(query: String) {
        if (importing) return
        lastQuery = query
        importing = true
        importText.text = "Searching..."
        importPanel.visibility = View.VISIBLE
        thread(name = "find") {
            val results = ArrayList<Triple<Int, String, SearchHit?>>()      // notebook page index, label, pdf hit
            try {
                // typed text
                for ((pi, pg) in note.pages.withIndex()) for (it in pg.items) {
                    if (it is TextItem) {
                        val at = it.text.indexOf(query, ignoreCase = true)
                        if (at >= 0) results.add(Triple(pi, it.text.substring(max(0, at - 20), min(it.text.length, at + query.length + 40)).replace('\n', ' '), null))
                    }
                }
                // PDF text, one PDF at a time
                val files = note.pages.mapNotNull { it.pdfFile }.distinct()
                for ((fi, f) in files.withIndex()) {
                    val pages = pdfText.ensureIndex(f) { i, n -> ui.post { importText.text = "Reading the text of PDF ${fi + 1} of ${files.size}...\n\npage $i of $n" } }
                    for (hit in pdfText.search(f, pages, query)) {
                        val ni = pageIndexOf(f, hit.pdfPage)
                        if (ni >= 0) results.add(Triple(ni, hit.snippet, hit))
                    }
                }
            } catch (e: Throwable) { Log.w("InkNotes", "find failed", e) }
            results.sortBy { it.first }
            ui.post {
                importPanel.visibility = View.GONE; importing = false
                if (results.isEmpty()) { say("Nothing found for \"$query\""); return@post }
                sheet.list("\"$query\": ${results.size} found", results.map { (ni, label, hit) ->
                    Row("Page ${ni + 1}", label) {
                        ink.goToPage(ni); refreshChrome()
                        if (hit != null) ink.showHits(pdfText.hitRects(hit.pdfFile, hit.pdfPage, query))
                    }
                })
            }
        }
    }

    private fun showOutline() {
        val pg = ink.page
        val f = pg.pdfFile ?: return
        thread(name = "outline") {
            val entries = pdfText.outline(f)
            ui.post {
                if (entries.isEmpty()) { say("This PDF has no outline"); return@post }
                sheet.list("PDF outline", entries.map { e ->
                    val ni = pageIndexOf(f, e.pdfPage)
                    Row("    ".repeat(e.level.coerceAtMost(4)) + e.title.ifBlank { "(untitled)" }, "page ${e.pdfPage + 1}", enabled = ni >= 0) { ink.goToPage(ni); refreshChrome() }
                })
            }
        }
    }

    private fun mmOf(units: Float) = units / 6.686f
    private fun sizeName(pg: PageData) = PageSize.of(pg.w, pg.h)?.label ?: "Custom"

    /** One place for the page background: paper, spacing and size, with "use on every page". */
    private fun showPaper() {
        val pg = ink.page
        if (pg.isPdf) {
            sheet.list("Paper", listOf(Row("This page is a PDF page", "Its background is the PDF. Add a blank page to choose paper.", enabled = false) { },
                Row("Add a blank page after this one") { ink.addPageAfterCurrent() }))
            return
        }
        val rows = ArrayList<Row>()
        for (p in Paper.values()) rows.add(Row(p.label.replaceFirstChar { it.uppercase() }, selected = pg.paper == p) { ink.setPaper(p); refreshChrome() })
        val spacings = listOf(5f, 6f, 7f, 8f, 9.5f, 12f, 15f)
        rows.add(Row("Spacing of lines and squares", "%.1f mm".format(mmOf(pg.spacing))) {
            sheet.list("Spacing", spacings.map { mm ->
                Row("%.1f mm".format(mm), selected = Math.abs(mmOf(pg.spacing) - mm) < 0.3f) { ink.setSpacing(mm * 6.686f) }
            })
        })
        rows.add(Row("Page size", sizeName(pg)) {
            sheet.choose("Page size", PageSize.values().map { it.label to it }, PageSize.of(pg.w, pg.h)) { ink.setPageSize(it); refreshChrome() }
        })
        rows.add(Row("Use this paper on every page", "Paper, spacing and size of this page; PDF pages are left alone") {
            sheet.confirm("Use on every page?", "All plain pages of this notebook get ${pg.paper.label}, ${sizeName(pg)}, spacing %.1f mm.".format(mmOf(pg.spacing)), "Apply to all") {
                ink.applyPaperToAll(); refreshChrome(); say("Paper applied to every page")
            }
        })
        sheet.list("Paper for page ${ink.pageIndex + 1}", rows)
    }

    private fun confirmDeletePage() {
        sheet.confirm("Delete page ${ink.pageIndex + 1}?", "Page ${ink.pageIndex + 1} of ${note.pages.size} and its writing will be removed.", "Delete page") {
            ink.deleteCurrentPage(); refreshChrome()
        }
    }

    private fun showSettings() {
        val s = ink.settings
        val sideOrder = listOf(Tool.ERASER, Tool.SELECT, Tool.HAND, Tool.HIGHLIGHTER, Tool.PEN)
        sheet.list("Settings", listOf(
            Row("Fast ink while writing", presets[presetIndex].first) {
                sheet.choose("Fast ink while writing", presets.mapIndexed { i, p -> p.first to i }, presetIndex) {
                    presetIndex = it; prefs.edit().putInt("preset", it).apply(); if (it == 0) eink.restore()
                }
            },
            Row("Redraw cleanly after writing", onOff(polish)) { polish = !polish; prefs.edit().putBoolean("polish", polish).apply(); showSettings() },
            Row("Hold pen still to snap shapes", onOff(s.shapeSnap)) { s.shapeSnap = !s.shapeSnap; saveToolSettings(); showSettings() },
            Row("Pen side button", ink.sideButtonTool.name.lowercase().replaceFirstChar { it.uppercase() }) {
                sheet.choose("While the side button is held", sideOrder.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to it }, ink.sideButtonTool) {
                    ink.sideButtonTool = it; prefs.edit().putInt("side", it.ordinal).apply()
                }
            },
            Row("Zoom shortcut", if (s.zoomTaps == 0) "Off" else "Tap ${s.zoomTaps} times in a row with the pen to zoom in or out") {
                sheet.choose("Zoom shortcut", listOf("Off" to 0, "Tap 3 times" to 3, "Tap 4 times" to 4), s.zoomTaps) { s.zoomTaps = it; saveToolSettings() }
            },
            Row("Shortcut zoom level", "${"%.0f".format(s.zoomTarget)}x the whole page") {
                sheet.choose("Zoom level", listOf("2x" to 2f, "3x" to 3f, "4x" to 4f), s.zoomTarget) { s.zoomTarget = it; saveToolSettings() }
            },
            Row("Shortcut zooms to", if (s.zoomSquares) "Fixed squares of the page" else "The spot I tap") {
                sheet.choose("Shortcut zooms to", listOf("The spot I tap" to false, "Fixed squares of the page" to true), s.zoomSquares) { s.zoomSquares = it; saveToolSettings() }
            },
            Row("Move along while zoomed in", onOff(s.autoAdvance) + " - lift the pen near the right edge and the page slides over") { s.autoAdvance = !s.autoAdvance; saveToolSettings(); showSettings() },
            Row("Keep the screen on", onOff(prefs.getBoolean("keepAwake", false))) {
                val on = !prefs.getBoolean("keepAwake", false)
                prefs.edit().putBoolean("keepAwake", on).apply()
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                showSettings()
            },
            Row("AI service", "Provider, API key and model") { assistant.showSettings() },
            Row("Device tools", "Probe, Android About, Developer options") { showDeviceTools() },
            Row("About Ink Notes", "Version ${packageManager.getPackageInfo(packageName, 0).versionName}", enabled = false) { }
        ))
    }

    private fun showDeviceTools() {
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        sheet.list("Device tools", listOf(
            Row("Open device test tools (Probe)") { save(); startActivity(Intent(this, ProbeActivity::class.java)) },
            Row("Android 'About' screen", "Tap Build number 7 times to unlock developer options") { openAbout() },
            Row("Developer options", "Developer options: ${onOff(dev)}   USB debugging: ${onOff(adb)}") { openDeveloperOptions() }
        ))
    }

    private fun showPages() { sheet.hide(); pagesPanel.show(); pagesPanel.visibility = View.VISIBLE }
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
        if (!ok) say("No About screen found on this firmware")
    }

    private fun openDeveloperOptions() {
        val ok = startFirst(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings")
        )
        if (!ok) say("Developer options are still locked: unlock them from the About screen first")
    }

    // ---- InkView.Host ------------------------------------------------------------------------

    override fun onPenDown() {
        ui.removeCallbacks(settleToQuality)
        if (sheet.isShowing) sheet.hide()
        val fast = presets[presetIndex].second ?: return
        if (!inFast) { inFast = true; eink.set(fast, "pen down") }
    }

    override fun onPenUp() {
        if (presets[presetIndex].second == null) return
        ui.removeCallbacks(settleToQuality)
        ui.postDelayed(settleToQuality, 700)
    }

    override fun onDocumentChanged() {
        dirty = true
        refreshChrome()
        updateSelectionBar()
        ui.removeCallbacks(saveNow)
        ui.postDelayed(saveNow, 2000)
    }

    // ---- keys --------------------------------------------------------------------------------

    private val toolCycle = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.SELECT, Tool.SHAPE, Tool.TEXT, Tool.TAPE, Tool.HAND)

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (!ready) return super.dispatchKeyEvent(ev)
        val code = ev.keyCode
        // While a PDF is imported or exported the pages must not change underneath it.
        if (importing && code != KeyEvent.KEYCODE_BACK) return true
        if (code == KeyEvent.KEYCODE_BACK) {
            if (sheet.isShowing) { if (ev.action == KeyEvent.ACTION_UP) sheet.hide(); return true }
            if (studyPanel.visibility == View.VISIBLE) { if (ev.action == KeyEvent.ACTION_UP) hideStudy(); return true }
            if (pagesPanel.visibility == View.VISIBLE) { if (ev.action == KeyEvent.ACTION_UP) hidePages(); return true }
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
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && !sheet.isShowing) {
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

    /** Writes the notebook if it changed (or always, with [force]). Never from a retired or half-built editor. */
    private fun save(force: Boolean = false) {
        if (!ready || retired) return
        prefs.edit().putInt("lastPage_$notebookName", ink.pageIndex).apply()
        if (!dirty && !force && file.exists()) return
        try { NoteStore.save(note, file, touch = dirty || !file.exists()); dirty = false } catch (t: Throwable) {
            say("Could not save: $t")
        }
    }

    // The panel mode is system-wide, so always hand it back when we leave.
    override fun onPause() {
        eink.restoreNow()
        if (!ready) { super.onPause(); return }
        ui.removeCallbacks(settleToQuality); ui.removeCallbacks(saveNow)
        inFast = false
        val changed = dirty
        save()
        if (changed && !retired) try { Thumbnailer.save(note.pages[0], pdfs, 360, File(file.parentFile, "$notebookName.png")) } catch (_: Throwable) { }
        super.onPause()
    }

    override fun onDestroy() {
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) { } }
        debugReceiver = null
        if (live === this) live = null
        if (!ready) { eink.restoreNow(); super.onDestroy(); return }
        if (AudioNotes.active != null) stopRecording()
        if (AudioNotes.playing != null) AudioNotes.stopPlayback()
        try { pdfText.close() } catch (_: Throwable) { }
        ui.removeCallbacks(playTick); ui.removeCallbacks(recTick)
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
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: return
                when (cmd) {
                    "tool_pen" -> selectTool(Tool.PEN)
                    "tool_marker" -> selectTool(Tool.HIGHLIGHTER)
                    "tool_calli" -> { selectTool(Tool.PEN); ink.tool = Tool.CALLIGRAPHY; selectTool(Tool.CALLIGRAPHY) }
                    "tool_shape" -> selectTool(Tool.SHAPE)
                    "tool_text" -> selectTool(Tool.TEXT)
                    "tool_tape" -> selectTool(Tool.TAPE)
                    "tool_erase" -> selectTool(Tool.ERASER)
                    "tool_select" -> selectTool(Tool.SELECT)
                    "tool_hand" -> selectTool(Tool.HAND)
                    "tool_options" -> showToolOptions()
                    "more" -> showMore()
                    "settings" -> showSettings()
                    "sheet_close" -> sheet.hide()
                    "tap_seq" -> {
                        // simulated quick pen taps (adb input is too slow to tap three times within a second)
                        val x = i.getFloatExtra("x", 500f); val y = i.getFloatExtra("y", 700f); val n = i.getIntExtra("n", 3)
                        for (k in 0 until n) {
                            ui.postDelayed({
                                val t = android.os.SystemClock.uptimeMillis()
                                ink.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
                            }, k * 230L)
                            ui.postDelayed({
                                val t = android.os.SystemClock.uptimeMillis()
                                ink.dispatchTouchEvent(android.view.MotionEvent.obtain(t - 60, t, android.view.MotionEvent.ACTION_UP, x, y, 0))
                            }, k * 230L + 70L)
                        }
                    }
                    "ai_probe" -> assistant.debugProbe(i.getStringExtra("p") ?: "GEMINI")
                    "ai_menu" -> assistant.showMenu()
                    "ai_settings" -> assistant.showSettings()
                    "study_demo" -> {
                        val st = assistant.study
                        if (st.cards.isEmpty()) {
                            st.cards.add(Card("What is NPV?", "The sum of discounted cash flows."))
                            st.cards.add(Card("What does IRR equal at the discount rate where NPV = 0?", "The internal rate of return."))
                            st.quiz.add(Question(QuizKind.MULTIPLE_CHOICE, "A project with NPV > 0 should be...", listOf("Accepted", "Rejected", "Delayed", "Ignored"), "Accepted", "Positive NPV adds value."))
                            st.quiz.add(Question(QuizKind.TRUE_FALSE, "A higher discount rate lowers NPV.", listOf("True", "False"), "True", "Future cash flows are worth less."))
                            st.quiz.add(Question(QuizKind.FILL_BLANK, "NPV of a fairly priced asset is ____.", emptyList(), "zero", ""))
                            assistant.saveStudy()
                        }
                        showStudy(st, i.getBooleanExtra("quiz", false))
                    }
                    "study_close" -> hideStudy()
                    "find" -> runFind(i.getStringExtra("q") ?: "the")
                    "outline" -> showOutline()
                    "paper_sheet" -> showPaper()
                    "page_size" -> PageSize.values().firstOrNull { it.name == i.getStringExtra("v") }?.let { ink.setPageSize(it); refreshChrome() }
                    "spacing" -> ink.setSpacing(i.getFloatExtra("v", 64f))
                    "zoom_at" -> ink.zoomAt(i.getFloatExtra("x", 700f), i.getFloatExtra("y", 800f), i.getFloatExtra("v", 3f))
                    "zoom_whole" -> ink.zoomOut()
                    "zoom_state" -> Log.d("InkDebug", "zoomRel=${ink.zoomRel}")
                    "ruler" -> ink.rulerOn = i.getBooleanExtra("v", true)
                    "smooth" -> { ink.settings.smoothing = i.getIntExtra("v", 1); saveToolSettings() }
                    "rec_start" -> if (AudioNotes.active == null) onRecTapped()
                    "rec_stop" -> if (AudioNotes.active != null) stopRecording()
                    "play" -> note.recordings.firstOrNull { it.id == i.getIntExtra("id", 1) }?.let { startPlayback(it, i.getIntExtra("from", 0)) }
                    "play_stop" -> stopPlaybackUi()
                    "replay" -> { replayOn = i.getBooleanExtra("v", true); updatePlayUi() }
                    "seek" -> { AudioNotes.seekTo(i.getIntExtra("ms", 0)); updatePlayUi() }
                    "audio_state" -> Log.d("InkDebug", "audio recs=" + note.recordings.joinToString { "#${it.id}:${it.durationMs}ms ${AudioNotes.fileOf(this@NotesActivity, it).length()}B" } +
                        " active=${AudioNotes.active?.id} playing=${AudioNotes.playing?.id} pos=${AudioNotes.positionMs()} isPlaying=${AudioNotes.isPlaying}")
                    "text" -> ink.addText(i.getStringExtra("t") ?: "Hello", i.getFloatExtra("x", 200f), i.getFloatExtra("y", 400f))
                    "image_path" -> i.getStringExtra("path")?.let { importImage(Uri.fromFile(File(it))) }
                    "eraser_partial" -> { ink.settings.eraserPartial = i.getBooleanExtra("v", true); ink.settings.eraserRadius = i.getFloatExtra("r", 18f) }
                    "paper" -> Paper.values().firstOrNull { it.name == i.getStringExtra("v") }?.let { ink.setPaper(it); refreshChrome() }
                    "shape_kind" -> UserShape.values().firstOrNull { it.name == i.getStringExtra("v") }?.let { ink.settings.shapeTool = it }
                    "snap" -> { ink.settings.shapeSnap = i.getBooleanExtra("v", true); saveToolSettings() }
                    "undo" -> ink.undo()
                    "redo" -> ink.redo()
                    "page_next" -> goPage(1)
                    "page_prev" -> goPage(-1)
                    "zoom_in" -> ink.zoomBy(1)
                    "zoom_out" -> ink.zoomBy(-1)
                    "add_page" -> ink.addPageAfterCurrent()
                    "save" -> save(force = true)
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
                    "gray" -> { ink.settings.penGray = i.getIntExtra("v", 0); ink.settings.hlGray = i.getIntExtra("h", ink.settings.hlGray) }
                }
                Log.d("InkDebug", "cmd=$cmd -> ${ink.describe()} einkMode=${EinkController.nameOf(eink.readMode())}")
            }
        }
        registerReceiver(debugReceiver, IntentFilter("app.einknotes.DEBUG"))
    }
}
