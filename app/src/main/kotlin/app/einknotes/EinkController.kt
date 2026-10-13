package app.einknotes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/**
 * Talks to the Hanvon firmware's android.os.EinkManager through reflection (it is not in the public SDK).
 * setMode(String) writes the Rockchip property sys.eink.mode, which tells the display controller which
 * waveform to use. Every change is read back so we know whether the system really accepted it.
 *
 * SAFETY: the mode is system-wide. [restore] puts back the mode that was active before we touched anything;
 * the activity calls it on pause, stop, destroy and on a crash.
 */
class EinkController(ctx: Context) {

    companion object {
        const val AUTO = "0"
        const val FULL_GC16 = "2"
        const val FULL_GL16 = "3"
        const val PART_GC16 = "7"
        const val PART_GL16 = "8"
        const val A2 = "12"
        const val A2_DITHER = "13"
        const val DU = "14"
        const val DU4 = "15"
        const val AUTO_DU = "22"

        val names = mapOf(
            "0" to "AUTO", "1" to "OVERLAY", "2" to "FULL_GC16", "3" to "FULL_GL16", "4" to "FULL_GLR16",
            "5" to "FULL_GLD16", "6" to "FULL_GCC16", "7" to "PART_GC16", "8" to "PART_GL16", "9" to "PART_GLR16",
            "10" to "PART_GLD16", "11" to "PART_GCC16", "12" to "A2", "13" to "A2_DITHER", "14" to "DU",
            "15" to "DU4", "16" to "A2_ENTER", "17" to "RESET", "22" to "AUTO_DU", "23" to "AUTO_DU4", "-1" to "NULL"
        )
        /** Modes that are meant to be temporary (fast, low quality). Never remember these as the "original". */
        private val temporary = setOf("12", "13", "14", "15", "16", "17", "22", "23", "-1")
        fun nameOf(code: String?) = if (code == null) "?" else "${names[code] ?: "mode"}($code)"
    }

    private val mgr: Any? = try { ctx.getSystemService("eink") } catch (_: Throwable) { null }
    private val setModeM = try { mgr?.javaClass?.getMethod("setMode", String::class.java) } catch (_: Throwable) { null }
    private val getModeM = try { mgr?.javaClass?.getMethod("getMode") } catch (_: Throwable) { null }
    private val fullM = try { mgr?.javaClass?.getMethod("sendOneFullFrame") } catch (_: Throwable) { null }

    private val prefs = ctx.getSharedPreferences("eink", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    val available get() = mgr != null && setModeM != null && getModeM != null

    /** The mode to restore. Taken from the system the first time, and never a leftover temporary mode. */
    val original: String

    init {
        val now = readMode()
        val saved = prefs.getString("original", null)
        original = when {
            now != null && now !in temporary -> { prefs.edit().putString("original", now).apply(); now }
            saved != null -> saved
            else -> PART_GC16
        }
    }

    /** Human-readable log of every request, for the report. */
    val history = ArrayList<String>()

    fun readMode(): String? = try { getModeM?.invoke(mgr)?.toString() } catch (_: Throwable) { null }

    /** Sets a mode on a background thread (the call crosses into the system server). Reports back on the UI thread. */
    fun set(mode: String, why: String, done: ((String) -> Unit)? = null) {
        if (!available) { done?.invoke("e-ink interface not available"); return }
        worker.execute {
            val t0 = SystemClock.uptimeMillis()
            val outcome = try {
                setModeM!!.invoke(mgr, mode)
                val back = readMode()
                "$why: asked ${nameOf(mode)} -> now ${nameOf(back)} (${SystemClock.uptimeMillis() - t0} ms)" +
                    if (back == mode) "" else "   NOT ACCEPTED"
            } catch (t: Throwable) {
                val c = if (t is InvocationTargetException) t.cause ?: t else t
                "$why: asked ${nameOf(mode)} FAILED: $c"
            }
            synchronized(history) { history.add(outcome); if (history.size > 200) history.removeAt(0) }
            if (done != null) ui.post { done(outcome) }
        }
    }

    fun fullRefresh(done: ((String) -> Unit)? = null) {
        if (fullM == null) { done?.invoke("sendOneFullFrame not available"); return }
        worker.execute {
            val outcome = try { fullM.invoke(mgr); "full refresh requested" }
            catch (t: Throwable) { "full refresh FAILED: ${(t as? InvocationTargetException)?.cause ?: t}" }
            synchronized(history) { history.add(outcome) }
            if (done != null) ui.post { done(outcome) }
        }
    }

    fun restore(done: ((String) -> Unit)? = null) = set(original, "restore", done)

    /** Blocking restore for lifecycle callbacks and crash handlers: the process may die right after. */
    fun restoreNow() { try { setModeM?.invoke(mgr, original) } catch (_: Throwable) { } }

    fun historyText(): String = synchronized(history) { history.joinToString("\n") }.ifEmpty { "(no mode changes were requested)" }
}
