package app.einknotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Ink Probe v0.2: a test bench for an e-ink tablet with a pen-only digitizer.
 *  - draws with the pen tip, erases with the eraser end, grey ink while the side button is held
 *  - logs pen events and hardware keys (keys are swallowed on the drawing screen so we can see them)
 *  - lets you compare three ways of putting ink on screen and rate each one
 *  - a guided test walks through hover, side button, eraser and all nine buttons
 *  - builds a device report, saves it, and can POST it to the Mac
 */
class ProbeActivity : Activity() {

    private val stats = Stats()
    private val runner = TestRunner()
    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private var logDirty = false

    private lateinit var host: FrameLayout
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var modeButton: Button
    private lateinit var pen: PenSurface

    private lateinit var testPanel: LinearLayout
    private lateinit var testTitle: TextView
    private lateinit var testText: TextView
    private lateinit var testLive: TextView
    private lateinit var testNextButton: Button

    private lateinit var reportPanel: LinearLayout
    private lateinit var reportText: TextView
    private lateinit var urlField: EditText

    private var modeIndex = 0
    private var lastReport = ""

    private val prefs get() = getSharedPreferences("probe", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeButton = button("Mode") { cycleMode() }
        bar.addView(modeButton, weightParams(2f))
        bar.addView(button("Clear") { pen.clearInk(); stats.resetLatency() }, weightParams())
        bar.addView(button("Test") { startTest() }, weightParams())
        bar.addView(button("Report") { showReport() }, weightParams())
        bar.addView(button("Exit") { finish() }, weightParams())
        root.addView(bar)

        val rate = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rate.addView(mono(12f).apply { text = "Speed of this mode (1 slow, 5 fast):" })
        for (n in 1..5) rate.addView(button("$n") { rateMode(n) }, weightParams(0.6f))
        root.addView(rate)

        status = mono(12f)
        logView = mono(11f)
        root.addView(status)
        root.addView(logView)

        host = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = FrameLayout(this)
        frame.addView(root)
        testPanel = buildTestPanel().also { it.visibility = View.GONE }
        frame.addView(testPanel)
        reportPanel = buildReportPanel().also { it.visibility = View.GONE }
        frame.addView(reportPanel)
        setContentView(frame)

        stats.descriptorSink = { runner.record(it) }
        KeyBus.sink = { e -> log(stats.onKey(e, "a11y")) }
        selectMode(0)
        ui.post(refresher)
    }

    // ---- modes and ratings -------------------------------------------------------------------

    private fun selectMode(i: Int) {
        modeIndex = i
        host.removeAllViews()
        pen = when (i) {
            0 -> PenView(this, stats, dirtyOnly = false)
            1 -> PenView(this, stats, dirtyOnly = true)
            else -> SurfacePenView(this, stats)
        }
        host.addView(pen.asView(), FrameLayout.LayoutParams(-1, -1))
        modeButton.text = "Mode: " + pen.modeName
        stats.resetLatency()
    }

    private fun cycleMode() = selectMode((modeIndex + 1) % 3)

    private fun rateMode(n: Int) {
        stats.ratings[pen.modeName] = n
        log("rated ${pen.modeName} = $n")
    }

    // ---- input: everything is observed here, before any view sees it --------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Taps on the test panel's own buttons must not be counted as part of a test step.
        stats.muted = testPanel.visibility == View.VISIBLE && ev.y > testPanel.height - 260
        log(stats.onMotion(ev, "touch"))
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        log(stats.onMotion(ev, "generic"))   // hover, button press/release
        return super.dispatchGenericMotionEvent(ev)
    }

    /** Swallow keys on the drawing and test screens so the side buttons show up here. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (reportPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)  // keyboard needs these
        log(stats.onKey(event))
        return true
    }

    // ---- guided test -------------------------------------------------------------------------

    private fun buildTestPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
            setPadding(24, 24, 24, 24)
        }
        testTitle = mono(20f)
        testText = mono(17f)
        testLive = mono(13f)
        p.addView(testTitle)
        p.addView(testText)
        p.addView(testLive)
        p.addView(button("Open accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val spacer = View(this)
        p.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        testNextButton = button("Next") { advanceTest() }
        bar.addView(testNextButton, weightParams(2f))
        bar.addView(button("Stop") { stopTest() }, weightParams())
        p.addView(bar)
        return p
    }

    private fun startTest() {
        runner.start()
        KeyBus.consume = true      // while testing, the buttons must not also act on the system
        testPanel.visibility = View.VISIBLE
        showStep()
    }

    private fun stopTest() {
        KeyBus.consume = false
        runner.stop()
        testPanel.visibility = View.GONE
    }

    private fun advanceTest() {
        runner.next()
        if (runner.finished) {
            testTitle.text = "Test finished"
            testText.text = "Close this and tap Report to see and send the results."
            testLive.text = ""
            testNextButton.text = "Close"
            testNextButton.setOnClickListener {
                stopTest()
                testNextButton.text = "Next"; testNextButton.setOnClickListener { advanceTest() }
            }
        } else showStep()
    }

    private fun showStep() {
        val s = runner.steps[runner.index]
        testTitle.text = "Step ${runner.index + 1}/${runner.steps.size}: ${s.title}"
        testText.text = s.instruction
        testLive.text = "events so far in this step: 0   key capture: ${if (KeyBus.connected) "ON" else "off"}"
    }

    // ---- logging / status --------------------------------------------------------------------

    private fun log(line: String) {
        logLines.addLast(line)
        while (logLines.size > 6) logLines.removeFirst()
        logDirty = true
    }

    /** Throttled on purpose: constant text updates would keep the e-ink panel refreshing. */
    private val refresher = object : Runnable {
        override fun run() {
            if (logDirty) {
                logDirty = false
                logView.text = logLines.joinToString("\n")
                status.text = "latency(app side) last=${stats.lastLatencyMs}ms avg=${"%.1f".format(stats.avgLatencyMs())}ms " +
                    "max=${stats.latMaxMs}ms events=${stats.totalEvents} keycapture=${if (KeyBus.connected) "ON" else "off"}"
                if (runner.running) testLive.text = "events so far in this step: ${runner.liveCount()}   key capture: ${if (KeyBus.connected) "ON" else "off"}"
            }
            ui.postDelayed(this, 500)
        }
    }

    // ---- report ------------------------------------------------------------------------------

    private fun buildReportPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isClickable = true
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("Back") { reportPanel.visibility = View.GONE }, weightParams())
        bar.addView(button("Send report") { sendReport(false) }, weightParams())
        bar.addView(button("Send report + files") { sendReport(true) }, weightParams())
        p.addView(bar)
        p.addView(mono(11f).apply {
            text = "'+ files' also copies 3 read-only system files to your Mac for analysis (framework.jar, services.jar, hvNote.apk, about 112 MB). Nothing is changed on the tablet."
        })

        urlField = EditText(this).apply {
            hint = "http://MAC-IP:8765/"
            setText(prefs.getString("url", ""))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 14f
        }
        p.addView(urlField)

        reportText = mono(10f).apply { text = "building report..." }
        val sv = ScrollView(this)
        sv.addView(reportText)
        p.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return p
    }

    private fun showReport() {
        reportPanel.visibility = View.VISIBLE
        reportText.text = "building report (scanning system files, a few seconds)..."
        val testsText = runner.reportText()
        Thread {
            val body = DeviceReport.build(this, stats, testsText)
            val header = "Ink Probe v0.2 report ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
            val props = DeviceReport.fullProps()
            lastReport = header + body + "\n==== FULL getprop ====\n" + props
            val saved = save(lastReport, props)
            ui.post { reportText.text = lastReport.take(60000) + "\n\nSaved: $saved" }
        }.start()
    }

    private fun save(report: String, props: String): String {
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            getExternalFilesDir(null)
        )
        for (d in dirs) {
            try {
                if (d == null) continue
                d.mkdirs()
                File(d, "ink-probe-report.txt").writeText(report)
                File(d, "ink-probe-getprop.txt").writeText(props)
                return d.absolutePath
            } catch (_: Throwable) { }
        }
        return "(could not save a file)"
    }

    private fun systemFiles(): List<Pair<String, File>> {
        val list = ArrayList<Pair<String, File>>()
        list.add("framework.jar" to File("/system/framework/framework.jar"))
        list.add("services.jar" to File("/system/framework/services.jar"))
        try { list.add("hvNote.apk" to File(packageManager.getApplicationInfo("hanvon.aebr.hvnote", 0).sourceDir)) } catch (_: Throwable) { }
        return list.filter { it.second.canRead() }
    }

    private fun sendReport(withFiles: Boolean) {
        val url = urlField.text.toString().trim()
        prefs.edit().putString("url", url).apply()
        if (url.isEmpty() || lastReport.isEmpty()) {
            reportText.text = "Type the Mac address first (http://MAC-IP:8765/) and wait for the report to finish.\n\n${lastReport.take(40000)}"
            return
        }
        val sb = StringBuilder("sending to $url ...\n")
        fun show(extra: String = "") = ui.post { reportText.text = sb.toString() + extra + "\n" + lastReport.take(40000) }
        show()
        Thread {
            try {
                sb.append("report: ").append(Uploader.postText(url, lastReport)).append('\n'); show()
                if (withFiles) {
                    for ((name, file) in systemFiles()) {
                        sb.append("sending $name (${file.length() / 1048576} MB) ...\n"); show()
                        var last = 0L
                        val r = Uploader.postFile(url, file, name) { sent, total ->
                            if (sent - last > 6_000_000 || sent == total) { last = sent; show("   $name ${sent / 1048576}/${total / 1048576} MB") }
                        }
                        sb.append("   $name: $r\n"); show()
                    }
                }
                sb.append("finish: ").append(Uploader.done(url)).append('\n'); show()
            } catch (t: Throwable) {
                sb.append("send failed: $t\n"); show()
            }
        }.start()
    }

    // ---- small view helpers ------------------------------------------------------------------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minHeight = 110
        setOnClickListener { onClick() }
    }

    private fun mono(size: Float) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(10, 4, 10, 4)
        gravity = Gravity.START
    }

    private fun weightParams(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] != PackageManager.PERMISSION_GRANTED) {
            log("storage permission denied: report will be saved in the app folder instead")
        }
    }
}
