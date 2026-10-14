package app.einknotes.notes.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One flashcard, scheduled with simple Leitner boxes. */
class Card(val front: String, val back: String, var box: Int = 0, var due: Long = 0L)

enum class QuizKind { MULTIPLE_CHOICE, TRUE_FALSE, FILL_BLANK }

class Question(val kind: QuizKind, val question: String, val choices: List<String>, val answer: String, val explanation: String)

/** Everything the AI made for one notebook: flashcards and quiz questions, plus transcripts of its recordings. */
class StudySet(val cards: MutableList<Card> = ArrayList(), val quiz: MutableList<Question> = ArrayList(),
               val transcripts: MutableMap<Int, List<Segment>> = HashMap()) {

    fun dueCards(now: Long): List<Card> = cards.filter { it.due <= now }

    fun toJson(): String {
        val o = JSONObject()
        o.put("cards", JSONArray().also { a -> for (c in cards) a.put(JSONObject().put("front", c.front).put("back", c.back).put("box", c.box).put("due", c.due)) })
        o.put("quiz", JSONArray().also { a -> for (q in quiz) a.put(JSONObject().put("kind", q.kind.name).put("question", q.question)
            .put("choices", JSONArray(q.choices)).put("answer", q.answer).put("explanation", q.explanation)) })
        val t = JSONObject()
        for ((id, segs) in transcripts) t.put(id.toString(), JSONArray().also { a -> for (s in segs) a.put(JSONObject().put("start", s.start.toDouble()).put("end", s.end.toDouble()).put("text", s.text)) })
        o.put("transcripts", t)
        return o.toString()
    }

    companion object {
        const val SIDECAR = ".study.json"

        fun fileFor(dir: File, notebookId: String) = File(dir, "$notebookId$SIDECAR")

        fun load(file: File): StudySet = if (!file.exists()) StudySet() else try { fromJson(file.readText()) } catch (_: Exception) { StudySet() }

        fun save(set: StudySet, file: File) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(set.toJson())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }

        fun fromJson(text: String): StudySet {
            val o = JSONObject(text)
            val s = StudySet()
            o.optJSONArray("cards")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c ->
                s.cards.add(Card(c.optString("front"), c.optString("back"), c.optInt("box"), c.optLong("due"))) } }
            o.optJSONArray("quiz")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { q -> parseQuestion(q)?.let { s.quiz.add(it) } } }
            o.optJSONObject("transcripts")?.let { t -> for (k in t.keys()) {
                val id = k.toIntOrNull() ?: continue
                val arr = t.optJSONArray(k) ?: continue
                s.transcripts[id] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Segment(it.optDouble("start").toFloat(), it.optDouble("end").toFloat(), it.optString("text")) }
            } }
            return s
        }

        private fun parseQuestion(q: JSONObject): Question? {
            val kindName = q.optString("kind", q.optString("type")).uppercase().replace("-", "_").replace(" ", "_")
            val kind = when {
                kindName.startsWith("MULTIPLE") -> QuizKind.MULTIPLE_CHOICE
                kindName.startsWith("TRUE") -> QuizKind.TRUE_FALSE
                kindName.startsWith("FILL") -> QuizKind.FILL_BLANK
                else -> return null
            }
            val question = q.optString("question").trim()
            val answer = q.optString("answer").trim()
            if (question.isEmpty() || answer.isEmpty()) return null
            val choicesArr = q.optJSONArray("choices")
            var choices = if (choicesArr != null) (0 until choicesArr.length()).map { choicesArr.optString(it) } else emptyList()
            if (kind == QuizKind.TRUE_FALSE) choices = listOf("True", "False")
            if (kind == QuizKind.MULTIPLE_CHOICE && (choices.size < 2 || choices.none { it.equals(answer, ignoreCase = true) })) return null
            return Question(kind, question, choices, answer, q.optString("explanation").trim())
        }

        /** The AI's answer to the "make study cards" request. */
        fun fromAi(text: String): StudySet {
            val json = try { JSONObject(AiClient.extractJson(text)) } catch (_: Exception) { throw AiException("The study cards were not in the expected form") }
            val s = StudySet()
            json.optJSONArray("flashcards")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { c ->
                val f = c.optString("front").trim(); val b = c.optString("back").trim()
                if (f.isNotEmpty() && b.isNotEmpty()) s.cards.add(Card(f, b))
            } }
            json.optJSONArray("quiz")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { q -> parseQuestion(q)?.let { s.quiz.add(it) } } }
            if (s.cards.isEmpty() && s.quiz.isEmpty()) throw AiException("No cards could be made from this")
            return s
        }

        val INTERVAL_DAYS = intArrayOf(0, 1, 3, 7, 14, 30)

        /** Rating: 0 again, 1 hard, 2 good, 3 easy. Returns the box and due time the card gets. */
        fun reschedule(card: Card, rating: Int, now: Long): Pair<Int, Long> {
            val box = when (rating) { 0 -> 0; 1 -> card.box; 2 -> card.box + 1; else -> card.box + 2 }.coerceIn(0, INTERVAL_DAYS.size - 1)
            val due = when (rating) {
                0 -> now                                              // shown again in this session
                1 -> now + 10 * 60 * 1000L
                else -> now + INTERVAL_DAYS[box] * 24L * 3600 * 1000
            }
            return box to due
        }
    }
}
