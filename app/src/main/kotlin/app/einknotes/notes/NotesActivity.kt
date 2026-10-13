package app.einknotes.notes

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
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
    private lateinit var eink: EinkController
    private val file by lazy { File(filesDir, "notes/notebook1.inkn") }
    private val prefs get() = getSharedPreferences("notes", Context.MODE_PRIVATE)

    // toolbar
    private lateinit var penBtn: Button
    private lateinit var eraseBtn: Button
    private lateinit var handBtn: Button
    private lateinit var undoBtn: Button
    private lateinit var redoBtn: Button
    private lateinit var pageLabel: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var inkModeBtn: Button
    private lateinit var polishBtn: Button
    private lateinit var paperBtn: Button
    private lateinit var sideBtn: Button
    private lateinit var devStatus: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eink = EinkController(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> eink.restoreNow(); prev?.uncaughtException(t, e) }

        presetIndex = prefs.getInt("preset", 1).coerceIn(0, presets.size - 1)
        polish = prefs.getBoolean("polish", false)

        note = NoteStore.load(file)
        ink = InkView(this, note, this)
        ink.sideButtonTool = Tool.values().getOrElse(prefs.getInt("side", Tool.ERASER.ordinal)) { Tool.ERASER }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val row1 = row()
        penBtn = tb("Pen") { selectTool(Tool.PEN) }
        eraseBtn = tb("Erase") { selectTool(Tool.ERASER) }
        handBtn = tb("Hand") { selectTool(Tool.HAND) }
        undoBtn = tb("Undo") { ink.undo() }
        redoBtn = tb("Redo") { ink.redo() }
        for (b in listOf(penBtn, eraseBtn, handBtn, undoBtn, redoBtn, tb("Zoom -") { ink.zoomBy(-1) }, tb("Zoom +") { ink.zoomBy(1) })) row1.addView(b, weight())
        val row2 = row()
        pageLabel = tb("1/1") { }
        for (b in listOf(tb("<") { goPage(-1) }, pageLabel, tb(">") { goPage(1) }, tb("+ Page") { ink.addPageAfterCurrent() },
            tb("Refresh") { eink.fullRefresh() }, tb("More") { showSettings() })) row2.addView(b, weight())
        root.addView(row1); root.addView(row2)
        root.addView(ink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = FrameLayout(this)
        frame.addView(root)
        settingsPanel = buildSettings().also { it.visibility = View.GONE }
        frame.addView(settingsPanel)
        setContentView(frame)

        selectTool(Tool.PEN)
        refreshChrome()
        registerDebugControl()
    }

    /**
     * Debug builds only (never in a release build): lets a developer drive the app over USB with
     *   adb shell am broadcast -a app.einknotes.DEBUG --es cmd <command>
     * commands: virtual_on, virtual_off, state, tool_pen, tool_erase, tool_hand, undo, redo, page_next, page_prev,
     * zoom_in, zoom_out, add_page, save. Results go to logcat under the tag InkDebug.
     */
    private fun registerDebugControl() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: return
                when (cmd) {
                    "tool_pen" -> selectTool(Tool.PEN)
                    "tool_erase" -> selectTool(Tool.ERASER)
                    "tool_hand" -> selectTool(Tool.HAND)
                    "undo" -> ink.undo()
                    "redo" -> ink.redo()
                    "page_next" -> goPage(1)
                    "page_prev" -> goPage(-1)
                    "zoom_in" -> ink.zoomBy(1)
                    "zoom_out" -> ink.zoomBy(-1)
                    "add_page" -> ink.addPageAfterCurrent()
                    "save" -> save()
                }
                Log.d("InkDebug", "cmd=$cmd -> ${ink.describe()} einkMode=${EinkController.nameOf(eink.readMode())}")
            }
        }, IntentFilter("app.einknotes.DEBUG"))
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

    private fun selectTool(t: Tool) {
        ink.tool = t
        mark(penBtn, t == Tool.PEN); mark(eraseBtn, t == Tool.ERASER); mark(handBtn, t == Tool.HAND)
    }

    private fun refreshChrome() {
        pageLabel.text = "${ink.pageIndex + 1}/${note.pages.size}"
        undoBtn.alpha = if (ink.canUndo) 1f else 0.35f
        redoBtn.alpha = if (ink.canRedo) 1f else 0.35f
        if (::paperBtn.isInitialized) {
            inkModeBtn.text = "Ink mode: " + presets[presetIndex].first
            polishBtn.text = "Polish after writing: " + (if (polish) "on" else "off")
            paperBtn.text = "Paper: " + ink.page.paper.label
            sideBtn.text = "Pen side button: " + ink.sideButtonTool.name.lowercase()
        }
    }

    private fun goPage(delta: Int) {
        val n = ink.pageIndex + delta
        if (n in note.pages.indices) { ink.goToPage(n); refreshChrome() }
    }

    // ---- settings ----------------------------------------------------------------------------

    private fun buildSettings(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
            setPadding(40, 40, 40, 40)
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
            val order = listOf(Tool.ERASER, Tool.HAND, Tool.PEN)
            ink.sideButtonTool = order[(order.indexOf(ink.sideButtonTool) + 1) % order.size]
            prefs.edit().putInt("side", ink.sideButtonTool.ordinal).apply()
            refreshChrome()
        }
        for (b in listOf(inkModeBtn, polishBtn, paperBtn, sideBtn,
            tb("Full screen refresh now") { eink.fullRefresh(); hideSettings() },
            tb("Open device test tools (Probe)") { save(); startActivity(Intent(this, ProbeActivity::class.java)) },
            tb("Android 'About' screen (tap Build number 7 times)") { openAbout() },
            tb("Developer options (after unlocking)") { openDeveloperOptions() },
            tb("Close") { hideSettings() })) {
            p.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        }
        devStatus = TextView(this).apply { textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 28, 0, 0) }
        p.addView(devStatus)
        p.addView(TextView(this).apply {
            text = "Buttons: Volume Up/Down turn pages. In the tablet's own settings you can also give buttons " +
                "Zoom In/Out, arrow keys, Page Up/Down, Tab (next tool) or Enter (new page); they work here."
            textSize = 14f; setTextColor(Color.BLACK); setPadding(0, 40, 0, 0)
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
    private fun hideSettings() { settingsPanel.visibility = View.GONE; ink.repaintAll() }

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
        ui.removeCallbacks(saveNow)
        ui.postDelayed(saveNow, 2000)
    }

    // ---- keys --------------------------------------------------------------------------------

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        if (settingsPanel.visibility == View.VISIBLE && code == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) hideSettings()
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
                KeyEvent.KEYCODE_TAB -> selectTool(when (ink.tool) { Tool.PEN -> Tool.ERASER; Tool.ERASER -> Tool.HAND; Tool.HAND -> Tool.PEN })
                KeyEvent.KEYCODE_ENTER -> ink.addPageAfterCurrent()
            }
        }
        return true
    }

    // ---- lifecycle / saving ------------------------------------------------------------------

    private fun save() {
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
        super.onPause()
    }

    override fun onDestroy() {
        eink.restoreNow()
        super.onDestroy()
    }
}
