package app.einknotes

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import java.io.File

/**
 * Read-only fact finding about the tablet. Nothing here changes any setting.
 * Every section is wrapped so one failure never hides the rest.
 */
object DeviceReport {

    private val interesting = Regex(
        "(?i)(eink|e-ink|epd|ebc|wacom|stylus|waveform|ghost|refresh|penstar|hanvon|handwrit|ink|pen\\b|pen\\.)"
    )

    fun build(ctx: Context, stats: Stats): String {
        val sb = StringBuilder()
        fun section(title: String, body: () -> String) {
            sb.append("\n==== ").append(title).append(" ====\n")
            sb.append(try { body() } catch (t: Throwable) { "ERROR: $t" }).append('\n')
        }

        section("Build") {
            """
            manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL}
            device=${Build.DEVICE} product=${Build.PRODUCT} hardware=${Build.HARDWARE} board=${Build.BOARD}
            android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} patch=${Build.VERSION.SECURITY_PATCH}
            fingerprint=${Build.FINGERPRINT}
            display=${Build.DISPLAY}
            supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}
            """.trimIndent()
        }

        section("Display") {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            val modes = d.supportedModes.joinToString("; ") { "${it.physicalWidth}x${it.physicalHeight}@${"%.1f".format(it.refreshRate)}" }
            "real=${m.widthPixels}x${m.heightPixels} dpi=${m.densityDpi} xdpi=${m.xdpi} ydpi=${m.ydpi} density=${m.density}\n" +
                "refreshRate=${d.refreshRate} modes=[$modes]"
        }

        section("Input devices (pen digitizer details are here)") {
            val out = StringBuilder()
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                out.append("[$id] ${dev.name} vendor=${dev.vendorId} product=${dev.productId} " +
                    "sources=0x${Integer.toHexString(dev.sources)} virtual=${dev.isVirtual} " +
                    "keyboardType=${dev.keyboardType}\n")
                for (r in dev.motionRanges) {
                    out.append("     axis=${MotionEvent.axisToString(r.axis)} src=0x${Integer.toHexString(r.source)} " +
                        "min=${r.min} max=${r.max} flat=${r.flat} fuzz=${r.fuzz} res=${r.resolution}\n")
                }
            }
            out.toString()
        }

        section("/proc/bus/input/devices") {
            File("/proc/bus/input/devices").readText().take(6000)
        }

        section("System features (pen / touch / e-ink related)") {
            ctx.packageManager.systemAvailableFeatures
                .mapNotNull { it.name }
                .filter { Regex("(?i)(touch|stylus|pen|ink|epd|screen)").containsMatchIn(it) }
                .sorted().joinToString("\n")
        }

        section("Installed packages matching e-ink / pen / note / launcher words") {
            val pm = ctx.packageManager
            val all = pm.getInstalledPackages(0)
            val rx = Regex("(?i)(eink|epd|ebc|penstar|wacom|hanvon|stylus|handwrit|ink|note|reader|launcher|system.?ui|setting)")
            "total packages=${all.size}\n" + all.map { it.packageName }.filter { rx.containsMatchIn(it) }.sorted().joinToString("\n")
        }

        section("Settings keys that look e-ink related (System / Global / Secure)") {
            val out = StringBuilder()
            val tables = listOf(
                "system" to Settings.System.CONTENT_URI,
                "global" to Settings.Global.CONTENT_URI,
                "secure" to Settings.Secure.CONTENT_URI
            )
            for ((label, uri) in tables) {
                try {
                    ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                        var total = 0
                        while (c.moveToNext()) {
                            total++
                            val name = c.getString(0) ?: continue
                            if (interesting.containsMatchIn(name)) out.append("$label: $name = ${c.getString(1)}\n")
                        }
                        out.append("($label total keys: $total)\n")
                    }
                } catch (t: Throwable) { out.append("$label: ERROR $t\n") }
            }
            out.toString()
        }

        section("System properties (filtered; full list saved to the report file)") {
            val props = run(arrayOf("getprop"))
            val keep = props.lines().filter {
                interesting.containsMatchIn(it) || it.contains("ro.product") || it.contains("ro.hardware") ||
                    it.contains("ro.board.platform") || it.contains("ro.build.description") || it.contains("ro.boot.hardware")
            }
            "total props=${props.lines().size}\n" + keep.take(120).joinToString("\n")
        }

        section("Vendor files: framework jars, system apps, kernel nodes") {
            val out = StringBuilder()
            fun names(path: String, filter: Regex? = null, limit: Int = 200) {
                val list = File(path).list()
                if (list == null) { out.append("$path: (not listable)\n"); return }
                val shown = list.sorted().filter { filter == null || filter.containsMatchIn(it) }.take(limit)
                out.append("$path: ${list.size} entries; shown: ${shown.joinToString(", ")}\n")
            }
            names("/system/framework", Regex("(?i)(eink|epd|ebc|wacom|pen|ink|vendor|ext|hanvon|rk|mtk)"))
            names("/system/priv-app")
            names("/system/app")
            names("/system_ext/priv-app")
            names("/system_ext/app")
            names("/vendor/app")
            names("/vendor/lib64", Regex("(?i)(eink|epd|ebc|wacom|pen|ink)"))
            names("/system/lib64", Regex("(?i)(eink|epd|ebc|wacom|pen|ink)"))
            for (p in listOf("/sys/class/graphics", "/sys/class/misc", "/dev/graphics")) names(p)
            for (p in listOf("/dev/ebc", "/dev/ebc_buf", "/sys/class/misc/ebc", "/sys/devices/platform/ebc-dev",
                "/sys/class/misc/mxc_epdc_fb", "/sys/class/graphics/fb0", "/sys/class/misc/eink")) {
                val f = File(p)
                out.append("$p exists=${f.exists()} readable=${f.canRead()} writable=${f.canWrite()}\n")
            }
            out.toString()
        }

        section("Framework methods that mention e-ink (hidden API listing)") {
            val out = StringBuilder()
            val rx = Regex("(?i)(eink|e_ink|epd|ebc|waveform|refreshmode|ghost|fullrefresh|partialrefresh|handwrit)")
            val classes = listOf(
                "android.view.View", "android.view.SurfaceView", "android.view.Surface", "android.view.Display",
                "android.view.Window", "android.view.ViewRootImpl", "android.view.SurfaceControl",
                "android.os.PowerManager", "android.hardware.input.InputManager", "android.os.SystemProperties"
            )
            for (name in classes) {
                try {
                    val c = Class.forName(name)
                    val hits = (c.declaredMethods.map { it.name } + c.declaredFields.map { it.name }).filter { rx.containsMatchIn(it) }.distinct()
                    out.append("$name: ${if (hits.isEmpty()) "no matches" else hits.joinToString()}\n")
                } catch (t: Throwable) { out.append("$name: not accessible ($t)\n") }
            }
            for (vendor in listOf("com.onyx.android.sdk.pen.TouchHelper", "com.onyx.android.sdk.api.device.epd.EpdController")) {
                out.append("$vendor: ${try { Class.forName(vendor); "PRESENT" } catch (t: Throwable) { "absent" }}\n")
            }
            out.toString()
        }

        section("Pen / key statistics from this session") { stats.summary() }
        return sb.toString()
    }

    fun fullProps(): String = run(arrayOf("getprop"))

    private fun run(cmd: Array<String>): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (t: Throwable) { "ERROR $t" }
}
