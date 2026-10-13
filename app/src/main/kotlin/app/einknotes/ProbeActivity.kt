package app.einknotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Ink Probe: a small test bench for an e-ink tablet with a pen-only digitizer.
 *  - draws with the pen tip, erases with the eraser end, grey ink while the side button is held
 *  - logs every pen event and every hardware key (all keys are swallowed so we can see them)
 *  - lets you compare three ways of putting ink on screen
 *  - writes a device report and can POST it to the Mac
 */
class ProbeActivity : Activity() {

    private val stats = Stats()
    private val ui = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private var logDirty = false

    private lateinit var host: FrameLayout
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var reportPanel: LinearLayout
    private lateinit var reportText: TextView
    private lateinit var urlField: EditText
    private lateinit var modeButton: Button
    private lateinit var pen: PenSurface
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
        bar.addView(modeButton, weightParams())
        bar.addView(button("Clear") { pen.clearInk(); stats.resetLatency() }, weightParams())
        bar.addView(button("Report") { showReport() }, weightParams())
        bar.addView(button("Exit") { finish() }, weightParams())
        root.addView(bar)

        status = mono(13f)
        logView = mono(11f)
        root.addView(status)
        root.addView(logView)

        host = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val frame = FrameLayout(this)
        frame.addView(root)

        reportPanel = buildReportPanel()
        reportPanel.visibility = View.GONE
        frame.addView(reportPanel)
        setContentView(frame)

        selectMode(0)
        ui.post(refresher)
    }

    // ---- modes -------------------------------------------------------------------------------

    private fun selectMode(i: Int) {
        modeIndex = i
        host.removeAllViews()
        pen = when (i) {
            0 -> PenView(this, stats, dirtyOnly = false, onLog = ::log)
            1 -> PenView(this, stats, dirtyOnly = true, onLog = ::log)
            else -> SurfacePenView(this, stats, onLog = ::log)
        }
        host.addView(pen.asView(), FrameLayout.LayoutParams(-1, -1))
        modeButton.text = "Mode: " + pen.modeName
        stats.resetLatency()
    }

    private fun cycleMode() = selectMode((modeIndex + 1) % 3)

    // ---- hardware keys -----------------------------------------------------------------------

    /** Swallow every key so the 9 side buttons show up here instead of acting on the system. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // On the report screen the on-screen keyboard also sends key events, so let those through.
        if (reportPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        log(stats.onKey(event))
        return true
    }

    // ---- logging / status --------------------------------------------------------------------

    private fun log(line: String) {
        logLines.addLast(line)
        while (logLines.size > 7) logLines.removeFirst()
        logDirty = true
    }

    /** Throttled on purpose: constant text updates would keep the e-ink panel refreshing. */
    private val refresher = object : Runnable {
        override fun run() {
            if (logDirty) {
                logDirty = false
                logView.text = logLines.joinToString("\n")
                status.text = "latency(app side) last=${stats.lastLatencyMs}ms avg=${"%.1f".format(stats.avgLatencyMs())}ms " +
                    "max=${stats.latMaxMs}ms events=${stats.totalEvents}"
            }
            ui.postDelayed(this, 400)
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
        bar.addView(button("Send to Mac") { sendReport() }, weightParams())
        p.addView(bar)

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
        reportText.text = "building report..."
        Thread {
            val body = DeviceReport.build(this, stats)
            val header = "Ink Probe report ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n"
            lastReport = header + body
            val saved = save(lastReport, DeviceReport.fullProps())
            ui.post { reportText.text = lastReport + "\n\nSaved: $saved" }
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

    private fun sendReport() {
        val url = urlField.text.toString().trim()
        prefs.edit().putString("url", url).apply()
        if (url.isEmpty() || lastReport.isEmpty()) {
            reportText.text = "Type the Mac address first (http://MAC-IP:8765/) and wait for the report to finish.\n\n$lastReport"
            return
        }
        reportText.text = "sending to $url ...\n\n$lastReport"
        Thread {
            val result = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 6000
                c.readTimeout = 10000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.outputStream.use { it.write(lastReport.toByteArray()) }
                "sent, server said HTTP ${c.responseCode}"
            } catch (t: Throwable) { "send failed: $t" }
            ui.post { reportText.text = "$result\n\n$lastReport" }
        }.start()
    }

    // ---- small view helpers ------------------------------------------------------------------

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = 120
        setOnClickListener { onClick() }
    }

    private fun mono(size: Float) = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(10, 4, 10, 4)
        gravity = Gravity.START
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] != PackageManager.PERMISSION_GRANTED) {
            log("storage permission denied: report will be saved in the app folder instead")
        }
    }
}
