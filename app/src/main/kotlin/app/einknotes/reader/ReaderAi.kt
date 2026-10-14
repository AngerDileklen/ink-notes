package app.einknotes.reader

import android.app.Activity
import android.os.Handler
import app.einknotes.notes.ai.AiClient
import app.einknotes.notes.ai.AiException
import app.einknotes.notes.ai.AiSettings
import app.einknotes.notes.ai.PagedText
import app.einknotes.notes.ai.StudySet
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import java.io.File
import kotlin.concurrent.thread

/**
 * AI for the book reader, text only: ask about the chapter, summaries, explain or translate a word or passage, and
 * flashcards from the chapter (added to the book's word list). Uses the service chosen in Settings > AI service.
 */
class ReaderAi(
    private val act: Activity,
    private val book: Book,
    private val settings: AiSettings,
    private val sheet: Sheet,
    private val ui: Handler,
    private val say: (String) -> Unit,
    private val work: (String?) -> Unit,
    /** the text of the chapter on screen (asynchronous: it comes from the page) */
    private val chapterText: ((String) -> Unit) -> Unit,
    private val chapterTitle: () -> String,
    private val study: () -> StudySet,
    private val saveStudy: () -> Unit,
    private val startStudy: (Boolean) -> Unit
) {
    private val SYSTEM = "You are a reading companion inside an ebook reader on a small e-ink tablet. The reader is learning English and Turkish. " +
        "Answer in the language of the question (or the language asked for). Be concise and accurate. Do not use markdown; write plain text with short paragraphs and simple dashes for lists."

    fun showMenu(selection: String? = null) {
        val rows = ArrayList<Row>()
        if (selection != null) {
            rows.add(Row("Explain the selection", selection.take(60)) { explain(selection, "") })
            rows.add(Row("Translate the selection") { translate(selection) })
        }
        rows.add(Row("Ask about this chapter", "A question about what you are reading") { ask() })
        rows.add(Row("Summarize this chapter", "Main takeaway and key points") { summarize() })
        rows.add(Row("Make flashcards from this chapter", "Added to this book's saved words") { makeCards() })
        rows.add(Row("Study saved words and flashcards", "${study().cards.size} cards", enabled = study().cards.isNotEmpty()) { startStudy(false) })
        rows.add(Row("Take the quiz", "${study().quiz.size} questions", enabled = study().quiz.isNotEmpty()) { startStudy(true) })
        rows.add(Row("AI service settings", settings.config().provider.label + if (settings.config().ready) "" else " - not set up") { settings.show() })
        sheet.list("AI", rows)
    }

    private fun needKey() = settings.needKey()

    private fun run(label: String, task: () -> String, title: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        work(label)
        thread(name = "reader-ai") {
            var result: String? = null; var error: String? = null
            try { result = task() } catch (e: AiException) { error = e.message }
            ui.post {
                work(null)
                result?.let { PagedText.show(act, sheet, title, it, actions) }
                error?.let { say(it) }
            }
        }
    }

    private fun withChapter(cfgLabel: String, use: (String) -> Unit) {
        work(cfgLabel)
        chapterText { text -> ui.post { work(null); if (text.isBlank()) say("There is no text on this page to use") else use(text.take(14000)) } }
    }

    fun ask() {
        val cfg = needKey() ?: return
        sheet.input("Ask about this chapter", "", hint = "Your question", multiline = true, ok = "Ask") { q ->
            if (q.isBlank()) return@input
            withChapter("Reading the chapter...") { text ->
                run("Asking ${cfg.provider.label}...", {
                    AiClient.complete(cfg, SYSTEM, "Book: ${book.title}${if (book.author.isNotEmpty()) " by ${book.author}" else ""}\nChapter: ${chapterTitle()}\n\nQuestion: $q\n\nText of the chapter:\n$text")
                }, "Answer")
            }
        }
    }

    fun summarize() {
        val cfg = needKey() ?: return
        withChapter("Reading the chapter...") { text ->
            run("Summarizing with ${cfg.provider.label}...", {
                AiClient.complete(cfg, SYSTEM, "Summarize this chapter of \"${book.title}\". Give one sentence with the main takeaway, then 3 to 7 dashes with the key points, then any terms worth remembering.\n\nChapter: ${chapterTitle()}\n\n$text")
            }, "Summary: ${chapterTitle()}")
        }
    }

    /** Explains a word or passage in context. */
    fun explain(text: String, sentence: String) {
        val cfg = needKey() ?: return
        val single = !text.trim().contains(' ')
        run("Asking ${cfg.provider.label}...", {
            AiClient.complete(cfg, SYSTEM,
                if (single) "Explain the word \"$text\" as used here: \"$sentence\".\nGive: its meaning in this context in simple English, part of speech, one other example sentence, and the Turkish translation. If the word is Turkish, explain it in Turkish and give the English translation."
                else "Explain this passage from \"${book.title}\" in simple terms (in the language of the passage, then a short Turkish version if it is English):\n\n\"$text\"" + (if (sentence.isNotEmpty() && sentence != text) "\n\nContext: \"$sentence\"" else ""))
        }, text.take(40))
    }

    fun translate(text: String) {
        val cfg = needKey() ?: return
        run("Translating...", {
            AiClient.complete(cfg, SYSTEM, "Translate the following into Turkish if it is English, or into English if it is Turkish. Reply with the translation only.\n\n$text")
        }, "Translation")
    }

    fun makeCards() {
        val cfg = needKey() ?: return
        withChapter("Reading the chapter...") { text ->
            work("Making flashcards with ${cfg.provider.label}...")
            thread(name = "reader-cards") {
                var made: StudySet? = null; var error: String? = null
                try {
                    made = StudySet.fromAi(AiClient.complete(cfg, SYSTEM,
                        "From this chapter of \"${book.title}\" make study material. Reply with JSON only in this form: " +
                            "{\"flashcards\":[{\"front\":\"term or question\",\"back\":\"meaning or answer\"}],\"quiz\":[{\"type\":\"multiple_choice|true_false|fill_blank\",\"question\":\"...\",\"choices\":[\"...\"],\"answer\":\"exact text of the correct choice, or True/False, or the missing word\",\"explanation\":\"one sentence\"}]}. " +
                            "Make 10 to 15 flashcards (key terms, ideas and useful vocabulary) and 5 to 8 quiz questions. Text:\n$text", json = true))
                } catch (e: AiException) { error = e.message }
                ui.post {
                    work(null)
                    made?.let { m ->
                        study().cards.addAll(m.cards); study().quiz.addAll(m.quiz); saveStudy()
                        sheet.list("Study cards made", listOf(
                            Row("${m.cards.size} flashcards and ${m.quiz.size} quiz questions added", enabled = false) { },
                            Row("Study now", enabled = m.cards.isNotEmpty()) { startStudy(false) },
                            Row("Take the quiz now", enabled = m.quiz.isNotEmpty()) { startStudy(true) }))
                    }
                    error?.let { say(it) }
                }
            }
        }
    }
}
