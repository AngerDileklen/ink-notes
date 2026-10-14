package app.einknotes.notes.ai

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.label

/**
 * Flashcards and quizzes on a full screen made for e-ink: one big card, a few large buttons, nothing scrolls.
 * Ratings use simple Leitner boxes (see [StudySet.reschedule]). [onChanged] is called after every rating so the
 * schedule is saved.
 */
class StudyPanel(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {

    private val title: TextView = context.label("", 24f, bold = true)
    private val progress: TextView = context.label("", 20f)
    private val body = TextView(context).apply {
        setTextColor(Color.BLACK); gravity = Gravity.CENTER; setPadding(40, 20, 40, 20)
        setAutoSizeTextTypeUniformWithConfiguration(22, 46, 2, TypedValue.COMPLEX_UNIT_SP)
    }
    private val extra: TextView = context.label("", 20f).apply { gravity = Gravity.CENTER; setPadding(40, 8, 40, 8) }
    private val actions = LinearLayout(context).apply { orientation = VERTICAL }
    private var set = StudySet()
    private var onChanged: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        isClickable = true
        setPadding(16, 8, 16, 12)
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 0, 0, 0) }
        head.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = 24 })
        head.addView(EButton(context, "close", "Close") { onClose() }.apply { bordered = false }, LayoutParams(150, Eink.BAR_H))
        addView(head)
        addView(View(context).apply { setBackgroundColor(Color.BLACK) }, LayoutParams(LayoutParams.MATCH_PARENT, 3))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(extra, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun button(text: String, onClick: () -> Unit) = EButton(context, null, text) { onClick() }.apply { textSize = 30f }

    private fun setButtons(vararg rows: List<Pair<String, () -> Unit>>) {
        actions.removeAllViews()
        for (r in rows) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((t, f) in r) row.addView(button(t, f), LayoutParams(0, Eink.BAR_H + 20, 1f).apply { setMargins(6, 6, 6, 6) })
            actions.addView(row)
        }
    }

    // ---- flashcards -----------------------------------------------------------------------------------------------

    fun startFlashcards(study: StudySet, changed: () -> Unit) {
        set = study; onChanged = changed
        title.text = "Flashcards"
        val queue = ArrayList(study.dueCards(System.currentTimeMillis()))
        val total = queue.size
        var done = 0
        fun next() {
            val card = queue.firstOrNull()
            if (card == null) { finished("You're all set.", "You studied $total ${if (total == 1) "card" else "cards"}. The rest come back on their due days."); return }
            progress.text = "${done + 1} of ${total}"
            body.text = card.front; extra.text = ""
            setButtons(listOf("Show answer" to {
                body.text = card.back; extra.text = card.front
                setButtons(listOf(
                    "Again" to { rate(card, 0, queue) { done++; next() } },
                    "Hard" to { rate(card, 1, queue) { done++; next() } },
                    "Good" to { rate(card, 2, queue) { done++; next() } },
                    "Easy" to { rate(card, 3, queue) { done++; next() } }))
            }))
        }
        next()
    }

    private fun rate(card: Card, rating: Int, queue: MutableList<Card>, then: () -> Unit) {
        val (box, due) = StudySet.reschedule(card, rating, System.currentTimeMillis())
        card.box = box; card.due = due
        queue.remove(card)
        if (rating <= 1) queue.add(card)                         // again / hard: see it once more in this session
        onChanged()
        then()
    }

    // ---- quiz -----------------------------------------------------------------------------------------------------

    fun startQuiz(study: StudySet) {
        set = study
        title.text = "Quiz"
        val qs = study.quiz.shuffled()
        var i = 0; var right = 0
        fun next() {
            if (i >= qs.size) { finished("Quiz finished", "$right of ${qs.size} correct."); return }
            val q = qs[i]
            progress.text = "${i + 1} of ${qs.size}"
            body.text = q.question; extra.text = ""
            fun reveal(correct: Boolean) {
                if (correct) right++
                extra.text = (if (correct) "Correct.  " else "Not quite.  ") + "Answer: ${q.answer}" + (if (q.explanation.isNotEmpty()) "\n${q.explanation}" else "")
                setButtons(listOf("Next" to { i++; next() }))
            }
            when (q.kind) {
                QuizKind.MULTIPLE_CHOICE, QuizKind.TRUE_FALSE ->
                    setButtons(*q.choices.map { c -> listOf(c to { reveal(c.equals(q.answer, ignoreCase = true)) }) }.toTypedArray())
                QuizKind.FILL_BLANK ->
                    setButtons(listOf("Show answer" to {
                        extra.text = "Answer: ${q.answer}" + (if (q.explanation.isNotEmpty()) "\n${q.explanation}" else "")
                        setButtons(listOf("I was wrong" to { reveal(false) }, "I was right" to { reveal(true) }))
                    }))
            }
        }
        next()
    }

    private fun finished(head: String, detail: String) {
        progress.text = ""
        body.text = head; extra.text = detail
        setButtons(listOf("Close" to { onClose() }))
    }
}
