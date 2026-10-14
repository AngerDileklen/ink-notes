package app.einknotes.notes.ai

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.notes.InkView
import app.einknotes.notes.NoteData
import app.einknotes.notes.PdfLibrary
import app.einknotes.notes.PdfTextEngine
import app.einknotes.notes.Recording
import app.einknotes.notes.TextItem
import app.einknotes.notes.Thumbnailer
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * The AI menu: ask about a page, summaries, flashcards and quizzes, transcripts, and the provider/key settings.
 * Page pictures and text leave the tablet only when one of these actions is chosen, and only to the service the
 * person picked in Settings > AI.
 */
class AiAssistant(
    private val act: Activity,
    private val note: NoteData,
    private val notebookId: String,
    private val pdfs: PdfLibrary,
    private val pdfText: PdfTextEngine,
    private val ink: InkView,
    private val sheet: Sheet,
    private val ui: Handler,
    private val say: (String) -> Unit,
    private val work: (String?) -> Unit,                       // shows "working" text, or null to hide it
    private val playFrom: (Recording, Int) -> Unit,
    private val startStudy: (StudySet, Boolean) -> Unit,       // set, quiz?
    private val markChanged: () -> Unit
) {
    private val notesDir = File(act.filesDir, "notes")
    val study: StudySet by lazy { StudySet.load(StudySet.fileFor(notesDir, notebookId)) }

    fun saveStudy() { try { StudySet.save(study, StudySet.fileFor(notesDir, notebookId)) } catch (_: Throwable) { } }

    fun dropTranscript(recId: Int) { if (study.transcripts.remove(recId) != null) saveStudy() }

    // ---- settings (shared with the book reader) -------------------------------------------------------------------

    val settings = AiSettings(act, sheet, ui, say, work)
    fun config() = settings.config()
    fun showSettings() = settings.show()
    fun debugProbe(providerName: String) = settings.debugProbe(providerName)

    // ---- menu -----------------------------------------------------------------------------------------------------

    fun showMenu() {
        val due = study.dueCards(System.currentTimeMillis()).size
        sheet.list("AI assistant", listOf(
            Row("Ask about this page", "A question about what is on the page") { ask() },
            Row("Summarize pages", "Main takeaway and key points") { askRange("Summarize pages", true) { a, b -> summarize(a, b) } },
            Row("Make flashcards and a quiz", "From the pages you choose") { askRange("Make study cards", false) { a, b -> makeCards(a, b) } },
            Row("Study flashcards", "${study.cards.size} cards, $due due now", enabled = study.cards.isNotEmpty()) { startStudy(study, false) },
            Row("Take the quiz", "${study.quiz.size} questions", enabled = study.quiz.isNotEmpty()) { startStudy(study, true) },
            Row("Transcribe a recording", if (note.recordings.isEmpty()) "No recordings yet" else "Turn speech into text you can search and tap", enabled = note.recordings.isNotEmpty()) { chooseRecording() },
            Row("Delete all study cards", enabled = study.cards.isNotEmpty() || study.quiz.isNotEmpty()) {
                sheet.confirm("Delete study cards?", "All flashcards and quiz questions of this notebook will be removed.", "Delete") { study.cards.clear(); study.quiz.clear(); saveStudy() }
            },
            Row("AI service settings", config().provider.label + if (config().ready) "" else " - not set up") { showSettings() }
        ))
    }

    private fun needKey(): AiConfig? = settings.needKey()

    // ---- page material --------------------------------------------------------------------------------------------

    private fun pngOf(pageIndex: Int): ByteArray {
        val bmp = Thumbnailer.render(note.pages[pageIndex], pdfs, 1300)
        val out = ByteArrayOutputStream()
        try { bmp.compress(Bitmap.CompressFormat.PNG, 90, out) } finally { bmp.recycle() }
        return out.toByteArray()
    }

    private fun textOf(pageIndex: Int): String {
        val pg = note.pages[pageIndex]
        val sb = StringBuilder()
        pg.pdfFile?.let { f -> pdfText.pageText(f, pg.pdfPage)?.text?.let { sb.append(it.take(6000)) } }
        for (it in pg.items) if (it is TextItem) sb.append("\n").append(it.text)
        return sb.toString().trim()
    }

    private val SYSTEM = "You are a study assistant inside a handwriting note-taking app. You are given pictures of notebook pages (a printed " +
        "document in the background with the student's handwriting on top) and, when available, the text of those pages. Answer in the language " +
        "of the question. Be concise and accurate. Do not use markdown; write plain text with short paragraphs and simple dashes for lists."

    // ---- ask ------------------------------------------------------------------------------------------------------

    private fun ask() {
        val cfg = needKey() ?: return
        val pi = ink.pageIndex
        sheet.input("Ask about page ${pi + 1}", "", hint = "Your question", multiline = true, ok = "Ask") { q ->
            if (q.isBlank()) return@input
            work("Asking ${cfg.provider.label}...")
            thread(name = "ai-ask") {
                var answer: String? = null; var error: String? = null
                try {
                    val text = textOf(pi)
                    answer = AiClient.complete(cfg, SYSTEM, "Question: $q" + (if (text.isNotEmpty()) "\n\nText of the page:\n$text" else ""), listOf(pngOf(pi)))
                } catch (e: AiException) { error = e.message }
                ui.post {
                    work(null)
                    answer?.let { a -> showText("Answer", a, listOf("Place on a new page" to { placeText(a) })) }
                    error?.let { say(it) }
                }
            }
        }
    }

    /** Asks "from page .. to page .." (default: the current page). */
    private fun askRange(title: String, summary: Boolean, go: (Int, Int) -> Unit) {
        val n = note.pages.size
        val cur = ink.pageIndex + 1
        sheet.input("$title: pages (e.g. $cur or $cur-${minOf(n, cur + 3)})", "$cur", hint = "From-to, at most 8 pages", ok = "Go") { t ->
            val parts = t.trim().split('-', '–').map { it.trim().toIntOrNull() }
            val a = (parts.getOrNull(0) ?: return@input).coerceIn(1, n)
            val b = (parts.getOrNull(1) ?: a).coerceIn(a, n)
            if (b - a + 1 > 8) { say("Choose at most 8 pages at a time"); return@input }
            go(a - 1, b - 1)
        }
    }

    private fun gather(from: Int, to: Int): Pair<List<ByteArray>, String> {
        val images = (from..to).map { pngOf(it) }
        val text = (from..to).joinToString("\n\n") { "Page ${it + 1}:\n" + textOf(it) }
        return images to text
    }

    // ---- summary --------------------------------------------------------------------------------------------------

    private fun summarize(from: Int, to: Int) {
        val cfg = needKey() ?: return
        work("Summarizing with ${cfg.provider.label}...")
        thread(name = "ai-summary") {
            var result: String? = null; var error: String? = null
            try {
                val (imgs, text) = gather(from, to)
                result = AiClient.complete(cfg, SYSTEM,
                    "Summarize these notebook pages (pages ${from + 1} to ${to + 1}). Give: one sentence with the main takeaway, then 3 to 7 dashes with the key points, " +
                        "then any definitions or formulas worth remembering. Text of the pages:\n$text", imgs)
            } catch (e: AiException) { error = e.message }
            ui.post {
                work(null)
                result?.let { r -> showText("Summary of pages ${from + 1}-${to + 1}", r, listOf("Place on a new page" to { placeText(r) })) }
                error?.let { say(it) }
            }
        }
    }

    // ---- study cards ----------------------------------------------------------------------------------------------

    private fun makeCards(from: Int, to: Int) {
        val cfg = needKey() ?: return
        work("Making study cards with ${cfg.provider.label}...")
        thread(name = "ai-cards") {
            var made: StudySet? = null; var error: String? = null
            try {
                val (imgs, text) = gather(from, to)
                val answer = AiClient.complete(cfg, SYSTEM,
                    "From these notebook pages (pages ${from + 1} to ${to + 1}) make study material. Reply with JSON only in this form: " +
                        "{\"flashcards\":[{\"front\":\"question or term\",\"back\":\"answer\"}],\"quiz\":[{\"type\":\"multiple_choice|true_false|fill_blank\"," +
                        "\"question\":\"...\",\"choices\":[\"...\"],\"answer\":\"exact text of the correct choice, or True/False, or the missing word\",\"explanation\":\"one sentence\"}]}. " +
                        "Make 10 to 15 flashcards and 6 to 8 quiz questions (mix the three types; multiple choice has 4 choices). Text of the pages:\n$text", imgs, json = true)
                made = StudySet.fromAi(answer)
            } catch (e: AiException) { error = e.message }
            ui.post {
                work(null)
                made?.let { m ->
                    study.cards.addAll(m.cards); study.quiz.addAll(m.quiz); saveStudy(); markChanged()
                    sheet.list("Study cards made", listOf(
                        Row("${m.cards.size} flashcards and ${m.quiz.size} quiz questions added", enabled = false) { },
                        Row("Study the flashcards now", enabled = m.cards.isNotEmpty()) { startStudy(study, false) },
                        Row("Take the quiz now", enabled = m.quiz.isNotEmpty()) { startStudy(study, true) }))
                }
                error?.let { say(it) }
            }
        }
    }

    // ---- transcripts ----------------------------------------------------------------------------------------------

    private fun chooseRecording() {
        sheet.list("Transcribe which recording?", note.recordings.map { r ->
            val has = study.transcripts.containsKey(r.id)
            Row("Recording ${r.id}", "${clock(r.durationMs)}" + if (has) "  ·  transcript ready" else "") { if (has) showTranscript(r) else transcribe(r) }
        })
    }

    private fun clock(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun transcribe(r: Recording) {
        val cfg = needKey() ?: return
        if (!cfg.provider.canHearAudio) { say("${cfg.provider.label} cannot listen to audio. Choose Google Gemini or OpenAI in AI service settings."); return }
        val file = File(notesDir, "audio/${r.file}")
        if (!file.exists()) { say("The recording file is missing"); return }
        work("Transcribing recording ${r.id}...")
        thread(name = "ai-transcribe") {
            var segs: List<Segment>? = null; var error: String? = null
            try { segs = AiClient.transcribe(cfg, file) { msg -> ui.post { work(msg) } } } catch (e: AiException) { error = e.message }
            ui.post {
                work(null)
                segs?.let { study.transcripts[r.id] = it; saveStudy(); markChanged(); showTranscript(r) }
                error?.let { say(it) }
            }
        }
    }

    fun showTranscript(r: Recording, page: Int = 0) {
        val segs = study.transcripts[r.id] ?: return
        sheet.list("Transcript of recording ${r.id}", segs.map { s ->
            Row(s.text.take(120), clock((s.start * 1000).toInt()), keepOpen = false) { playFrom(r, (s.start * 1000).toInt()) }
        }, startPage = page, footer = listOf(
            EButton(act, null, "Place as text") { placeText(segs.joinToString("\n") { "[" + clock((it.start * 1000).toInt()) + "] " + it.text }) }.apply { textSize = 26f },
            EButton(act, null, "Redo") { study.transcripts.remove(r.id); saveStudy(); transcribe(r) }.apply { textSize = 26f }))
    }

    // ---- showing and placing text ---------------------------------------------------------------------------------

    /** A long answer shown a screen at a time. */
    fun showText(title: String, text: String, actions: List<Pair<String, () -> Unit>> = emptyList(), page: Int = 0) =
        PagedText.show(act, sheet, title, text, actions, page)

    /** Puts text on new pages after the current one (about a page's worth each). */
    private fun placeText(text: String) {
        val chunks = PagedText.paginate(text, 1500)
        for (c in chunks) { ink.addPageAfterCurrent(); ink.addText(c, 70f, 90f, ink.page.w - 140f) }
        say("Added ${chunks.size} ${if (chunks.size == 1) "page" else "pages"}")
    }
}
