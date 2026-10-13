package app.einknotes

import android.view.KeyEvent
import android.view.MotionEvent

/** Collects what the pen, eraser end and hardware keys actually send, so we design for facts. */
class Stats {
    class ToolStat {
        var events = 0L
        var downs = 0
        var minPressure = Float.MAX_VALUE
        var maxPressure = -Float.MAX_VALUE
        var maxTilt = 0f
        var maxDistance = 0f
        val buttonStates = sortedSetOf<Int>()
        val actions = sortedSetOf<String>()
        var samples = 0L          // includes batched "historical" samples
        var contactMs = 0L        // total time between DOWN and UP
        val pressureSteps = sortedSetOf<Float>()
    }

    val tools = linkedMapOf<String, ToolStat>()
    val keys = linkedMapOf<String, Int>()
    val ratings = linkedMapOf<String, Int>()   // drawing mode -> your 1..5 speed rating
    var totalEvents = 0L

    /** Gets a short, de-duplicated description of every input event (used by the guided test). */
    var descriptorSink: ((String) -> Unit)? = null
    @Volatile var muted = false

    // App-side latency: pen event -> end of the draw it caused. Lower bound only: the panel adds more.
    var latSumMs = 0L
    var latCount = 0L
    var latMaxMs = 0L
    var lastLatencyMs = 0L

    private var downTime = 0L

    fun toolName(e: MotionEvent): String = when (val t = e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TOOL_$t"
    }

    fun buttonNames(state: Int): String {
        if (state == 0) return "-"
        val parts = ArrayList<String>()
        if (state and MotionEvent.BUTTON_PRIMARY != 0) parts.add("PRIMARY")
        if (state and MotionEvent.BUTTON_SECONDARY != 0) parts.add("SECONDARY")
        if (state and MotionEvent.BUTTON_TERTIARY != 0) parts.add("TERTIARY")
        if (state and MotionEvent.BUTTON_STYLUS_PRIMARY != 0) parts.add("STYLUS_PRIMARY")
        if (state and MotionEvent.BUTTON_STYLUS_SECONDARY != 0) parts.add("STYLUS_SECONDARY")
        if (state and MotionEvent.BUTTON_BACK != 0) parts.add("BACK")
        if (state and MotionEvent.BUTTON_FORWARD != 0) parts.add("FORWARD")
        return parts.joinToString("+").ifEmpty { "0x" + Integer.toHexString(state) }
    }

    /** Records one motion event and returns a one-line description for the on-screen log. */
    fun onMotion(e: MotionEvent, source: String): String {
        totalEvents++
        val tool = toolName(e)
        val st = tools.getOrPut(tool) { ToolStat() }
        val action = MotionEvent.actionToString(e.actionMasked)
        st.events++
        st.actions.add(action)
        st.buttonStates.add(e.buttonState)
        val p = e.pressure
        if (p < st.minPressure) st.minPressure = p
        if (p > st.maxPressure) st.maxPressure = p
        if (st.pressureSteps.size < 400) st.pressureSteps.add(p)
        val tilt = e.getAxisValue(MotionEvent.AXIS_TILT)
        if (tilt > st.maxTilt) st.maxTilt = tilt
        val dist = e.getAxisValue(MotionEvent.AXIS_DISTANCE)
        if (dist > st.maxDistance) st.maxDistance = dist
        st.samples += (e.historySize + 1)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { st.downs++; downTime = e.eventTime }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> st.contactMs += (e.eventTime - downTime)
        }
        val dev = e.device?.name ?: "?"
        if (!muted) descriptorSink?.invoke("MOTION tool=$tool action=$action btn=${buttonNames(e.buttonState)} dev=$dev")
        return "$source $tool $action p=${"%.3f".format(p)} btn=${buttonNames(e.buttonState)} " +
            "xy=${e.x.toInt()},${e.y.toInt()} h=${e.historySize}"
    }

    fun onKey(e: KeyEvent, via: String = "app"): String {
        val dev = e.device?.name ?: "dev#${e.deviceId}"
        val label = "$via ${KeyEvent.keyCodeToString(e.keyCode)} scan=${e.scanCode} dev=$dev src=0x${Integer.toHexString(e.source)}"
        val phase = if (e.action == KeyEvent.ACTION_DOWN) (if (e.repeatCount > 0) "repeat" else "down") else "up"
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) keys[label] = (keys[label] ?: 0) + 1
        if (!muted) descriptorSink?.invoke("KEY $phase $label flags=0x${Integer.toHexString(e.flags)}")
        return "KEY $phase $label"
    }

    fun recordLatency(ms: Long) {
        // Ignore absurd values caused by redraws that were not triggered by the pen.
        if (ms < 0 || ms > 5000) return
        lastLatencyMs = ms
        latSumMs += ms
        latCount++
        if (ms > latMaxMs) latMaxMs = ms
    }

    fun avgLatencyMs(): Double = if (latCount == 0L) 0.0 else latSumMs.toDouble() / latCount

    fun resetLatency() {
        latSumMs = 0; latCount = 0; latMaxMs = 0; lastLatencyMs = 0
    }

    fun summary(): String {
        val sb = StringBuilder()
        if (tools.isEmpty()) sb.append("(no pen/touch events yet)\n")
        for ((name, s) in tools) {
            val hz = if (s.contactMs > 0) s.samples * 1000.0 / s.contactMs else 0.0
            sb.append("tool=$name events=${s.events} strokes=${s.downs} samples=${s.samples}\n")
            sb.append("  actions=${s.actions}\n")
            sb.append("  buttonStates=${s.buttonStates.map { buttonNames(it) }}\n")
            if (s.minPressure <= s.maxPressure)
                sb.append("  pressure min=${"%.4f".format(s.minPressure)} max=${"%.4f".format(s.maxPressure)} distinctValues=${s.pressureSteps.size}\n")
            sb.append("  maxTilt=${"%.2f".format(s.maxTilt)} maxHoverDistance=${"%.2f".format(s.maxDistance)}\n")
            sb.append("  approxSampleRate=${"%.0f".format(hz)} Hz (while touching)\n")
        }
        sb.append("hardware keys that reached the app:")
        sb.append(if (keys.isEmpty()) " (none)\n" else "\n")
        for ((k, n) in keys) sb.append("  $k  x$n\n")
        sb.append("your speed ratings (1 slow .. 5 fast): ")
        sb.append(if (ratings.isEmpty()) "(none given)\n" else ratings.entries.joinToString("; ") { "${it.key}=${it.value}" } + "\n")
        sb.append("app-side latency of the CURRENT mode (pen event -> end of draw, lower bound): " +
            "avg=${"%.1f".format(avgLatencyMs())} ms max=$latMaxMs ms n=$latCount\n")
        return sb.toString()
    }
}
