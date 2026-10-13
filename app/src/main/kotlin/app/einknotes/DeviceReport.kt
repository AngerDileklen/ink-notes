package app.einknotes

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import java.io.File
import java.util.zip.ZipFile

/**
 * Read-only fact finding about the tablet. Nothing here changes any setting.
 * Every section is wrapped so one failure never hides the rest.
 */
object DeviceReport {

    private val interesting = Regex(
        "(?i)(eink|e-ink|epd|ebc|wacom|stylus|waveform|ghost|refresh|penstar|hanvon|handwrit|ink|pen\\b|pen\\.)"
    )

    fun build(ctx: Context, stats: Stats, tests: String, einkLab: String): String {
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

        section("Shared libraries registered with the system") {
            (ctx.packageManager.systemSharedLibraryNames ?: emptyArray()).sorted().joinToString("\n")
        }

        section("Permission XML files from the vendor") {
            val out = StringBuilder()
            val rx = Regex("(?i)(hanvon|hv|eink|ebc|epd|rockchip|pen|ink|wacom)")
            for (d in listOf("/system/etc/permissions", "/vendor/etc/permissions", "/system_ext/etc/permissions",
                "/product/etc/permissions", "/odm/etc/permissions")) {
                val l = File(d).list()
                out.append("$d: ${l?.size ?: "(not listable)"} files; matching: ${l?.sorted()?.filter { rx.containsMatchIn(it) }?.joinToString(", ") ?: ""}\n")
            }
            out.toString()
        }

        section("Hanvon and reader apps: what they declare") {
            val pm = ctx.packageManager
            val out = StringBuilder()
            val pkgs = listOf("hanvon.aebr.hvnote", "hanvon.aebr.hvtextnote", "hanvon.aebr.hvmyscriptrecognition",
                "hanvon.aebr.hvsettings", "com.xodo.pdf.reader", "com.microsoft.office.onenote")
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SHARED_LIBRARY_FILES
            for (pkg in pkgs) {
                try {
                    val pi = pm.getPackageInfo(pkg, flags)
                    val ai = pi.applicationInfo
                    out.append("--- $pkg v${pi.versionName} system=${ai.flags and ApplicationInfo.FLAG_SYSTEM != 0}\n")
                    out.append("    apk=${ai.sourceDir} size=${File(ai.sourceDir).length() / 1024}KB\n")
                    File(ai.nativeLibraryDir).list()?.let { out.append("    native libs: ${it.sorted().joinToString()}\n") }
                    ai.sharedLibraryFiles?.let { out.append("    shared lib files: ${it.joinToString()}\n") }
                    val perms = pi.requestedPermissions?.toList() ?: emptyList()
                    out.append("    permissions: ${perms.size}; non-standard/notable: " +
                        perms.filter { !it.startsWith("android.permission.") || it.contains("SETTINGS") || it.contains("ALERT_WINDOW") || it.contains("ACCESSIBILITY") }
                            .joinToString() + "\n")
                    out.append("    services: ${pi.services?.joinToString { it.name.substringAfterLast('.') }?.take(900)}\n")
                    out.append("    activities(${pi.activities?.size}): ${pi.activities?.joinToString { it.name.substringAfterLast('.') }?.take(900)}\n")
                } catch (t: Throwable) { out.append("--- $pkg: $t\n") }
            }
            out.toString()
        }

        section("Vendor EinkManager: what an app can see of it (read-only)") { einkManagerInfo(ctx) }

        section("E-ink related strings inside vendor/system code (read-only scan)") {
            val out = StringBuilder()
            val pm = ctx.packageManager
            fun apk(pkg: String) = try { pm.getApplicationInfo(pkg, 0).sourceDir } catch (_: Throwable) { null }
            out.append(scanStrings("/vendor/lib64/libeink.so", einkRegex, 150))
            out.append(scanStrings("/system/framework/framework.jar", einkRegex, 80))
            out.append(scanStrings("/system/framework/services.jar", Regex("(?i)(eink|ebc[a-z_/]|hanvon[a-z_]*|customkey)"), 80))
            apk("hanvon.aebr.hvnote")?.let { out.append(scanStrings(it, einkRegex, 120)) }
            apk("hanvon.aebr.hvsettings")?.let {
                out.append(scanStrings(it, Regex("(?i)(pen_?key|pen_switch|btnStatus|usercustomkey|keytype|penbutton)"), 80))
            }
            out.toString()
        }

        section("Guided test results (pen + the nine buttons)") { tests }

        section("Display mode lab: what the system accepted") { einkLab }
        section("Pen / key statistics from this session") { stats.summary() }
        return sb.toString()
    }


    private val einkRegex = Regex("(?i)(eink|e-ink|ebc[a-z_/]|/dev/ebc|epd[a-z_]|waveform|dither|refreshmode|fullrefresh|a2mode|fastmode|regal)")

    /**
     * Lists printable-text runs that match [rx], from a plain file or from every .dex inside a jar/apk.
     * This only READS the file; it is how we discover which e-ink calls the vendor software uses.
     */
    private fun scanStrings(path: String, rx: Regex, limit: Int): String {
        val f = File(path)
        if (!f.exists()) return "$path: missing\n"
        if (!f.canRead()) return "$path: not readable\n"
        val found = linkedSetOf<String>()
        fun scan(bytes: ByteArray) {
            val sb = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xff
                if (c in 32..126) {
                    if (sb.length < 300) sb.append(c.toChar())
                } else {
                    if (sb.length >= 5 && rx.containsMatchIn(sb) && found.size < limit) found.add(sb.toString().take(170))
                    sb.setLength(0)
                }
            }
        }
        try {
            if (path.endsWith(".so")) scan(f.readBytes())
            else ZipFile(f).use { z ->
                for (e in z.entries()) {
                    if (e.name.endsWith(".dex")) scan(z.getInputStream(e).readBytes())
                    else if (e.name.startsWith("lib/") && e.name.contains("arm64")) found.add("[lib in apk] ${e.name}")
                    if (found.size >= limit) break
                }
            }
        } catch (t: Throwable) { return "$path: scan error $t\n" }
        return "$path (${f.length() / 1024}KB): ${found.size} matches\n" + found.joinToString("\n") { "    $it" } + "\n"
    }


    /**
     * The framework on this tablet contains android.os.EinkManager (a vendor addition). This lists what the
     * runtime lets a normal app see of it, and calls ONLY zero-argument getters. It never sets any mode.
     */
    private fun einkManagerInfo(ctx: Context): String {
        val out = StringBuilder()
        // 1) the service name
        val names = linkedSetOf<String>()
        try {
            val f = Context::class.java.getField("EINK_SERVICE")
            out.append("Context.EINK_SERVICE = ${f.get(null)}\n"); names.add(f.get(null) as String)
        } catch (t: Throwable) { out.append("Context.EINK_SERVICE: not readable ($t)\n") }
        names.addAll(listOf("eink", "einkservice", "eink_service", "EinkService"))

        // 2) the manager object
        var manager: Any? = null
        for (n in names) {
            try {
                val o = ctx.getSystemService(n)
                out.append("getSystemService(\"$n\") -> ${o?.javaClass?.name ?: "null"}\n")
                if (o != null && manager == null) manager = o
            } catch (t: Throwable) { out.append("getSystemService(\"$n\") threw $t\n") }
        }

        // 3) the class itself
        try {
            val c = Class.forName("android.os.EinkManager")
            out.append("class android.os.EinkManager found\n")
            val methods = c.declaredMethods.sortedBy { it.name }
            out.append("declared methods visible to apps: ${methods.size}\n")
            for (m in methods) out.append("   ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})\n")
            val fields = c.declaredFields.sortedBy { it.name }
            out.append("declared fields visible to apps: ${fields.size}\n")
            for (f in fields) {
                val v = try { f.isAccessible = true; if (java.lang.reflect.Modifier.isStatic(f.modifiers)) f.get(null) else "(instance)" } catch (t: Throwable) { "(unreadable)" }
                out.append("   ${f.type.simpleName} ${f.name} = $v\n")
            }
            // zero-argument getters only
            if (manager != null) {
                for (m in methods.filter { it.parameterTypes.isEmpty() && Regex("^(get|is)[A-Z]").containsMatchIn(it.name) }) {
                    val r = try { m.isAccessible = true; m.invoke(manager) } catch (t: Throwable) { "threw ${t.cause ?: t}" }
                    out.append("   CALL ${m.name}() = $r\n")
                }
            }
        } catch (t: Throwable) { out.append("android.os.EinkManager: $t\n") }

        // 4) the binder interface and its transaction numbers
        for (cn in listOf("android.os.IEinkManager\$Stub", "android.os.IEinkManager")) {
            try {
                val c = Class.forName(cn)
                val fs = c.declaredFields.sortedBy { it.name }
                out.append("$cn: ${fs.size} fields, ${c.declaredMethods.size} methods\n")
                for (f in fs) {
                    val v = try { f.isAccessible = true; f.get(null) } catch (t: Throwable) { "?" }
                    out.append("   ${f.name} = $v\n")
                }
                for (m in c.declaredMethods.sortedBy { it.name }) out.append("   m ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})\n")
            } catch (t: Throwable) { out.append("$cn: $t\n") }
        }
        return out.toString()
    }

    fun fullProps(): String = run(arrayOf("getprop"))

    private fun run(cmd: Array<String>): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (t: Throwable) { "ERROR $t" }
}
