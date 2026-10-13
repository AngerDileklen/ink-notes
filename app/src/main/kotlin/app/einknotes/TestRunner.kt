package app.einknotes

/**
 * A guided walk through the pen and the nine hardware buttons. While a step is active, every input
 * event is counted under that step, so the report says exactly what each action produced.
 * A step that shows "(nothing reached the app)" means the system consumed that input.
 */
class TestRunner {
    class Step(val title: String, val instruction: String)

    class Result(val step: Step) {
        val seen = linkedMapOf<String, Int>()
    }

    val steps: List<Step> = buildList {
        add(Step("Hover", "Hold the pen tip about 1 cm ABOVE the screen, not touching. Move it slowly for 3 seconds. Then tap Next with the pen."))
        add(Step("Side button, pen in the air", "Keep the pen off the screen. Press and release the pen's SIDE BUTTON 3 times. Then tap Next."))
        add(Step("Side button while writing", "HOLD the side button, draw a short line, release the button. Then tap Next."))
        add(Step("Eraser end, light", "Flip the pen. Touch the eraser end to the screen LIGHTLY and slide it. Then tap Next."))
        add(Step("Eraser end, hard", "Press the eraser end down HARD (feel the spring) and slide it. Then tap Next."))
        for (n in 1..9) add(Step("Button $n", "Hardware button $n, counting from the TOP with the tablet upright and the buttons on the left. Press and release it once, then tap Next."))
        add(Step("Button 1 long press", "Hold button 1 for 2 seconds, release. Then tap Next."))
    }

    var index = -1
        private set
    val results = ArrayList<Result>()

    val running get() = index in steps.indices
    val finished get() = index >= steps.size

    fun start() {
        results.clear()
        index = 0
        results.add(Result(steps[0]))
    }

    fun next() {
        if (!running) return
        index++
        if (index < steps.size) results.add(Result(steps[index]))
    }

    fun stop() { index = -1 }

    fun record(desc: String) {
        if (!running) return
        val seen = results.last().seen
        seen[desc] = (seen[desc] ?: 0) + 1
    }

    fun liveCount(): Int = if (running) results.last().seen.values.sum() else 0

    fun reportText(): String {
        if (results.isEmpty()) return "(guided test was not run)"
        val sb = StringBuilder()
        for (r in results) {
            sb.append("[").append(r.step.title).append("]\n")
            if (r.seen.isEmpty()) sb.append("   (nothing reached the app)\n")
            for ((k, n) in r.seen) sb.append("   ").append(k).append("  x").append(n).append('\n')
        }
        if (!finished) sb.append("(test not finished)\n")
        return sb.toString()
    }
}
