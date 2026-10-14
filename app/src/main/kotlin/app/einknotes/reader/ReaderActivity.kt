package app.einknotes.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.einknotes.EinkController
import app.einknotes.notes.ai.AiSettings
import app.einknotes.notes.ai.Card
import app.einknotes.notes.ai.StudyPanel
import app.einknotes.notes.ai.StudySet
import app.einknotes.notes.ui.EButton
import app.einknotes.notes.ui.Eink
import app.einknotes.notes.ui.Notice
import app.einknotes.notes.ui.Row
import app.einknotes.notes.ui.Sheet
import app.einknotes.notes.ui.label
import java.io.File
import kotlin.concurrent.thread

/**
 * The ebook reader. Pages turn with a jump (volume keys, the arrows below, a tap at the page edges). Tap a word for its
 * meaning from the offline English/Turkish dictionary, drag over text to highlight it or ask the AI about it.
 */
class ReaderActivity : Activity(), HtmlBookView.Host {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var book: Book
    private lateinit var state: ReadingState
    private lateinit var frame: FrameLayout
    private lateinit var sheet: Sheet
    private lateinit var notice: Notice
    private lateinit var titleView: TextView
    private lateinit var progressLabel: TextView
    private lateinit var strip: StripView
    private lateinit var pageArea: FrameLayout
    private lateinit var contextBar: LinearLayout
    private lateinit var workPanel: LinearLayout
    private lateinit var workText: TextView
    private lateinit var studyPanel: StudyPanel
    private lateinit var eink: EinkController
    private var backend: ReaderBackend? = null
    private var html: HtmlBookView? = null
    private lateinit var dictionary: Dictionary
    private lateinit var aiSettings: AiSettings
    private lateinit var ai: ReaderAi
    private val prefs get() = getSharedPreferences("reader", Context.MODE_PRIVATE)
    private var ready = false
    private var busy = false

    // the passage dragged over (waiting for an action)
    private var selText = ""; private var selStart = 0; private var selEnd = 0

    /** The word list and flashcards of this book. */
    private val study: StudySet by lazy { StudySet.load(File(BookStore.dir(this, book.id), "words.study.json")) }
    private fun saveStudy() { try { StudySet.save(study, File(BookStore.dir(this, book.id), "words.study.json")) } catch (_: Throwable) { } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        eink = EinkController(this)
        val id = intent?.getStringExtra("book")
        val b = id?.let { BookStore.load(this, it) }
        if (b == null) { finish(); return }
        book = b
        state = BookStore.loadState(this, book.id)
        dictionary = Dictionary(this)
        thread(name = "dict-warmup") { dictionary.available }          // copies the dictionary on first use
        if (prefs.getBoolean("keepAwake", false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        ready = true
        openBook()
    }

    // ---- screen -------------------------------------------------------------------------------------------------------

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 4, 6, 0) }
        top.addView(EButton(this, "back", "Library") { finish() }.apply { bordered = false }, LinearLayout.LayoutParams(130, Eink.BAR_H))
        titleView = label(book.title, 21f, bold = true).apply { setPadding(14, 0, 10, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        fun tb(icon: String?, text: String?, w: Int, f: () -> Unit) = top.addView(EButton(this, icon, text) { f() }.apply { bordered = false }, LinearLayout.LayoutParams(w, Eink.BAR_H))
        tb("pages", "Contents", 130) { showContents() }
        tb(null, "Aa", 100) { showTextSettings() }
        tb("search", "Find", 100) { askFind() }
        tb("ai", "AI", 100) { showAi() }
        tb("more", null, 90) { showMore() }
        root.addView(top)
        root.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, 3))

        pageArea = FrameLayout(this)
        root.addView(pageArea, LinearLayout.LayoutParams(-1, 0, 1f))
        contextBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); setPadding(8, 8, 8, 8); visibility = View.GONE }
        pageArea.addView(contextBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        strip = StripView(this)
        root.addView(strip, LinearLayout.LayoutParams(-1, 8))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(6, 0, 6, 4) }
        bottom.addView(EButton(this, "left", null) { turn(false) }.apply { bordered = false }, LinearLayout.LayoutParams(160, Eink.BAR_H))
        progressLabel = label("", 20f).apply { gravity = Gravity.CENTER }
        bottom.addView(progressLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(EButton(this, "right", null) { turn(true) }.apply { bordered = false }, LinearLayout.LayoutParams(160, Eink.BAR_H))
        root.addView(bottom)

        frame = FrameLayout(this)
        frame.addView(root)
        studyPanel = StudyPanel(this) { hideStudy() }
        studyPanel.visibility = View.GONE
        frame.addView(studyPanel)
        workPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true; setPadding(60, 400, 60, 60); visibility = View.GONE }
        workText = label("", 22f)
        workPanel.addView(workText)
        frame.addView(workPanel)
        setContentView(frame)
        sheet = Sheet(this, frame)
        notice = Notice(frame)
        aiSettings = AiSettings(this, sheet, ui, { say(it) }, { setWork(it) })
        ai = ReaderAi(this, book, aiSettings, sheet, ui, { say(it) }, { setWork(it) },
            { cb -> html?.chapterText(cb) ?: cb("") }, { backend?.chapterTitle() ?: "" }, { study }, { saveStudy() }, { quiz -> showStudy(quiz) })
    }

    private fun say(text: String) { if (::notice.isInitialized) notice.show(text) }

    private fun setWork(text: String?) {
        if (text == null) { workPanel.visibility = View.GONE; busy = false } else { busy = true; workText.text = text; workPanel.visibility = View.VISIBLE }
    }

    private fun showStudy(quiz: Boolean) {
        studyPanel.visibility = View.VISIBLE
        if (quiz) studyPanel.startQuiz(study) else studyPanel.startFlashcards(study) { saveStudy() }
    }

    private fun hideStudy() { studyPanel.visibility = View.GONE }

    private inner class StripView(ctx: Context) : View(ctx) {
        private val p = Paint()
        override fun onDraw(c: Canvas) {
            p.color = Eink.LIGHT; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = Color.BLACK; c.drawRect(0f, 0f, width * (backend?.percent() ?: 0f), height.toFloat(), p)
        }
    }

    // ---- opening --------------------------------------------------------------------------------------------------------

    private fun openBook() {
        when (book.format) {
            "epub", "fb2", "txt", "html", "mobi" -> {
                val v = HtmlBookView(this, book, BookStore.content(this, book.id), this)
                v.fontPx = prefs.getInt("font", 17); v.family = prefs.getString("family", "serif") ?: "serif"
                v.lineHeight = prefs.getFloat("lineHeight", 1.5f); v.marginPx = prefs.getInt("margin", 22)
                html = v; backend = v
                pageArea.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
                v.open(state.chapter.coerceIn(0, book.spine.size - 1), state.fraction)
            }
            else -> { say("This kind of book is not supported yet"); finish() }
        }
        refreshChrome()
    }

    private fun refreshChrome() {
        val b = backend ?: return
        titleView.text = book.title + if (b.chapterTitle().isNotBlank() && book.format != "pdf") "\n" + b.chapterTitle() else ""
        progressLabel.text = b.label() + "  ·  " + (b.percent() * 100).toInt() + "%"
        strip.invalidate()
    }

    // ---- HtmlBookView.Host -------------------------------------------------------------------------------------------------

    private var turns = 0
    private val savePosition = Runnable { savePositionNow() }

    override fun onPosition() {
        refreshChrome()
        ui.removeCallbacks(savePosition); ui.postDelayed(savePosition, 1500)
        val every = prefs.getInt("cleanEvery", 8)
        if (every > 0 && ++turns >= every) { turns = 0; ui.postDelayed({ if (!isFinishing) eink.fullRefresh() }, 500) }
        hideContext()
    }

    private fun savePositionNow() {
        backend?.savePosition(state)
        try { BookStore.saveState(this, book.id, state) } catch (_: Throwable) { }
    }

    override fun highlightsFor(chapter: Int) = state.highlights.filter { it.chapter == chapter }

    override fun onBlankTap() { hideContext(); sheet.hide() }

    override fun onWord(word: String, sentence: String, start: Int, end: Int, highlightId: String) {
        hideContext()
        showDefinition(word, sentence, start, end, highlightId, false)
    }

    override fun onSelection(text: String, start: Int, end: Int) {
        selText = text; selStart = start; selEnd = end
        contextBar.removeAllViews()
        fun btn(t: String, f: () -> Unit) = contextBar.addView(EButton(this, null, t) { f() }.apply { textSize = 26f }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 6 })
        if (text.trim().split(Regex("\\s+")).size <= 3) btn("Meaning") { hideContext(); showDefinition(text, "", selStart, selEnd, "", false) }
        btn("Highlight") { addHighlight(selText, selStart, selEnd); hideContext() }
        btn("Explain") { val t = selText; hideContext(); ai.explain(t, "") }
        btn("Translate") { val t = selText; hideContext(); ai.translate(t) }
        btn("Copy") { copy(selText); hideContext() }
        btn("✕") { hideContext() }
        contextBar.visibility = View.VISIBLE
    }

    private fun hideContext() { contextBar.visibility = View.GONE; html?.clearSelection() }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("book", text))
        say("Copied")
    }

    // ---- highlights -------------------------------------------------------------------------------------------------------------

    private fun addHighlight(text: String, start: Int, end: Int) {
        val v = html ?: return
        val h = Highlight(v.chapter, start, end, text.take(300), System.currentTimeMillis())
        state.highlights.add(h)
        v.addHighlight(start, end, h.time)
        savePositionNow()
    }

    private fun removeHighlight(id: String) {
        state.highlights.removeAll { it.time.toString() == id }
        html?.removeHighlight(id)
        savePositionNow()
    }

    private fun showHighlights() {
        if (state.highlights.isEmpty()) { say("No highlights yet. Drag the pen over some text and choose Highlight."); return }
        val sorted = state.highlights.sortedWith(compareBy({ it.chapter }, { it.start }))
        sheet.list("Highlights (${sorted.size})", sorted.map { h ->
            val ch = book.toc.lastOrNull { it.href.substringBefore('#') == book.spine.getOrNull(h.chapter) }?.title ?: "Section ${h.chapter + 1}"
            Row(h.text.take(110), ch) {
                sheet.list("Highlight", listOf(
                    Row("Go there") { html?.openAtOffset(h.chapter, h.start) },
                    Row("Delete this highlight") { if (h.chapter == html?.chapter) removeHighlight(h.time.toString()) else { state.highlights.remove(h); savePositionNow() } },
                    Row("Copy the text") { copy(h.text) }))
            }
        })
    }

    // ---- dictionary ---------------------------------------------------------------------------------------------------------------

    private fun showDefinition(word: String, sentence: String, start: Int, end: Int, hlId: String, expanded: Boolean) {
        val res = dictionary.lookup(word)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val text = if (res == null) "No entry for \"$word\" in the offline dictionary.\nTap Explain to ask the AI." else definitionText(res, expanded)
        box.addView(TextView(this).apply { this.text = text; textSize = 21f; setTextColor(Color.BLACK); setLineSpacing(0f, 1.12f); setPadding(6, 6, 6, 14) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = bar.addView(EButton(this, null, t) { f() }.apply { textSize = 24f }, LinearLayout.LayoutParams(0, Eink.BAR_H, 1f).apply { leftMargin = 6 })
        val shown = res?.englishLemma ?: res?.turkishHeadword ?: word
        if (hlId.isNotEmpty()) btn("Remove highlight") { sheet.hide(); removeHighlight(hlId) }
        else btn("Highlight") { sheet.hide(); addHighlight(word, start, end) }
        if (res != null) btn("Save word") { saveWord(shown, res, sentence) }
        btn("Explain") { sheet.hide(); ai.explain(word, sentence) }
        if (res != null) btn(if (expanded) "Fewer" else "More") { showDefinition(word, sentence, start, end, hlId, !expanded) }
        box.addView(bar)
        sheet.show(res?.let { it.englishLemma ?: it.turkishHeadword ?: it.query } ?: word, box)
    }

    private fun definitionText(res: DictResult, expanded: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        fun bold(t: String) { val s = sb.length; sb.append(t); sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        fun italic(t: String) { val s = sb.length; sb.append(t); sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val tr = book.language.lowercase().startsWith("tr")
        val sections = ArrayList<() -> Unit>()
        if (res.hasEnglish) sections.add {
            res.englishLemma?.let { italic("form of \"$it\"\n") }
            val groups = res.meanings.groupBy { it.pos }
            val perPos = if (expanded) 6 else 2
            for ((pos, list) in groups.entries.take(if (expanded) 4 else 2)) {
                italic(pos); sb.append("\n")
                list.take(perPos).forEachIndexed { i, m ->
                    sb.append("${i + 1}. ${m.gloss}\n")
                    if (i == 0 && m.example.isNotEmpty()) sb.append("   “${m.example}”\n")
                }
            }
            res.toTurkish?.let { t ->
                bold("Turkish: "); sb.append(t.words.take(if (expanded) 12 else 6).joinToString(", ")).append("\n")
                if (expanded) for (line in t.senses.take(4)) sb.append("  • ").append(line.replace(" => ", " → ")).append("\n")
            }
        }
        if (res.fromTurkish != null) sections.add {
            bold("Turkish word"); sb.append(if (res.turkishHeadword != null && res.turkishHeadword != res.query.lowercase()) " (${res.turkishHeadword})" else "").append(": ")
            sb.append(res.fromTurkish.words.take(if (expanded) 12 else 6).joinToString(", ")).append("\n")
            if (expanded) for (line in res.fromTurkish.senses.take(4)) sb.append("  • ").append(line.replace(" => ", " → ")).append("\n")
        }
        val ordered = if (tr && res.fromTurkish != null) sections.reversed() else sections
        for ((i, s) in ordered.withIndex()) { if (i > 0) sb.append("\n"); s() }
        return sb.trimEnd()
    }

    private fun saveWord(word: String, res: DictResult, sentence: String) {
        if (study.cards.any { it.front.equals(word, ignoreCase = true) }) { say("\"$word\" is already in your word list"); return }
        val back = StringBuilder()
        res.meanings.firstOrNull()?.let { back.append("(${it.pos}) ${it.gloss}\n") }
        res.toTurkish?.let { back.append("Turkish: ").append(it.words.take(5).joinToString(", ")).append("\n") }
        res.fromTurkish?.let { back.append("English: ").append(it.words.take(5).joinToString(", ")).append("\n") }
        if (sentence.isNotBlank()) back.append("“").append(sentence.take(200)).append("”")
        study.cards.add(Card(word, back.toString().trim()))
        saveStudy()
        say("Saved \"$word\" to this book's word list (${study.cards.size})")
    }

    // ---- menus --------------------------------------------------------------------------------------------------------------------

    private fun showAi() = ai.showMenu(selText.takeIf { contextBar.visibility == View.VISIBLE })

    private fun showContents() {
        val v = html ?: return
        val entries = book.toc.filter { book.spine.contains(it.href.substringBefore('#')) }
        sheet.list("Contents", entries.map { e -> Row("    ".repeat(e.level.coerceAtMost(3)) + e.title.ifBlank { "(untitled)" }) { v.openHref(e.href) } })
    }

    private fun showMore() {
        sheet.list(book.title.take(40), listOf(
            Row("Highlights", "${state.highlights.size}") { showHighlights() },
            Row("Saved words", "${study.cards.size} words", enabled = study.cards.isNotEmpty()) { showWords() },
            Row("Study saved words", enabled = study.cards.isNotEmpty()) { showStudy(false) },
            Row("Go to a place in the book", "A percentage") { askPercent() },
            Row("Clean the screen", "One full refresh to clear ghosting") { eink.fullRefresh() },
            Row("Clean automatically", prefs.getInt("cleanEvery", 8).let { if (it == 0) "Off" else "after every $it page turns" }) {
                sheet.choose("Clean the screen", listOf("Off" to 0, "Every 4 pages" to 4, "Every 8 pages" to 8, "Every 15 pages" to 15), prefs.getInt("cleanEvery", 8)) { prefs.edit().putInt("cleanEvery", it).apply() }
            },
            Row("Keep the screen on", if (prefs.getBoolean("keepAwake", false)) "On" else "Off") {
                val on = !prefs.getBoolean("keepAwake", false); prefs.edit().putBoolean("keepAwake", on).apply()
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            },
            Row("About this book", "${book.format.uppercase()}${if (book.author.isNotEmpty()) " · " + book.author else ""}${if (book.language.isNotEmpty()) " · " + book.language else ""}", enabled = false) { },
            Row("Dictionary credits", "English meanings: Princeton WordNet 3.0. Translations: WikDict (Wiktionary, CC BY-SA 3.0).", enabled = false) { }
        ))
    }

    private fun showWords() {
        sheet.list("Saved words (${study.cards.size})", study.cards.map { c ->
            Row(c.front, c.back.lines().firstOrNull() ?: "") {
                sheet.list(c.front, listOf(Row(c.back.take(200), enabled = false) { }, Row("Remove from the list") { study.cards.remove(c); saveStudy() }))
            }
        })
    }

    private fun askPercent() {
        sheet.input("Go to a place in the book", "", hint = "0 - 100 (percent)", ok = "Go") { t ->
            val p = t.trim().toFloatOrNull() ?: return@input
            val v = html ?: return@input
            val f = (p / 100f).coerceIn(0f, 1f)
            val sizes = book.spineSizes
            val total = sizes.sum().toFloat()
            if (sizes.size == book.spine.size && total > 0) {
                var acc = 0f
                for (i in sizes.indices) { if (acc + sizes[i] >= f * total || i == sizes.size - 1) { v.open(i, if (sizes[i] > 0) ((f * total - acc) / sizes[i]).coerceIn(0f, 1f) else 0f); return@input }; acc += sizes[i] }
            } else v.open((f * book.spine.size).toInt(), 0f)
        }
    }

    private fun showTextSettings() {
        val v = html ?: return
        val sizes = listOf(13, 14, 15, 16, 17, 18, 20, 22, 24, 27, 30)
        fun apply() { prefs.edit().putInt("font", v.fontPx).putString("family", v.family).putFloat("lineHeight", v.lineHeight).putInt("margin", v.marginPx).apply(); v.relayout() }
        sheet.list("Text", listOf(
            Row("Text size", "${v.fontPx}") { sheet.choose("Text size", sizes.map { ("$it" + if (it == 17) "  (normal)" else "") to it }, v.fontPx) { v.fontPx = it; apply() } },
            Row("Bigger text") { v.fontPx = sizes.firstOrNull { it > v.fontPx } ?: v.fontPx; apply() },
            Row("Smaller text") { v.fontPx = sizes.lastOrNull { it < v.fontPx } ?: v.fontPx; apply() },
            Row("Typeface", if (v.family == "serif") "Serif" else "Sans") { sheet.choose("Typeface", listOf("Serif" to "serif", "Sans" to "sans"), v.family) { v.family = it; apply() } },
            Row("Line spacing", "${v.lineHeight}") { sheet.choose("Line spacing", listOf("Tight" to 1.3f, "Normal" to 1.5f, "Loose" to 1.7f, "Very loose" to 1.9f), v.lineHeight) { v.lineHeight = it; apply() } },
            Row("Margins", "${v.marginPx}") { sheet.choose("Margins", listOf("Narrow" to 12, "Normal" to 22, "Wide" to 36, "Very wide" to 52), v.marginPx) { v.marginPx = it; apply() } }
        ))
    }

    // ---- find in book -----------------------------------------------------------------------------------------------------------------

    private var lastQuery = ""

    private fun askFind() {
        sheet.input("Find in this book", lastQuery, hint = "Word or phrase", ok = "Find") { q -> if (q.isNotBlank()) runFind(q.trim()) }
    }

    private class Hit(val chapter: Int, val ordinal: Int, val snippet: String)

    private fun runFind(query: String) {
        lastQuery = query
        setWork("Searching the book...")
        thread(name = "reader-find") {
            val hits = ArrayList<Hit>()
            val dir = BookStore.content(this, book.id)
            for ((ci, path) in book.spine.withIndex()) {
                val text = try { plain(BookImporter.decodeText(File(dir, path).readBytes())) } catch (_: Throwable) { continue }
                var at = text.indexOf(query, 0, ignoreCase = true); var n = 0
                while (at >= 0 && hits.size < 150) {
                    val from = maxOf(0, at - 40); val to = minOf(text.length, at + query.length + 60)
                    hits.add(Hit(ci, n, (if (from > 0) "…" else "") + text.substring(from, to) + (if (to < text.length) "…" else "")))
                    n++; at = text.indexOf(query, at + query.length, ignoreCase = true)
                }
                if (hits.size >= 150) break
            }
            ui.post {
                setWork(null)
                if (hits.isEmpty()) { say("Nothing found for \"$query\""); return@post }
                sheet.list("\"$query\": ${hits.size}${if (hits.size >= 150) "+" else ""} found", hits.map { h ->
                    val ch = book.toc.lastOrNull { it.href.substringBefore('#') == book.spine[h.chapter] }?.title ?: "Section ${h.chapter + 1}"
                    Row(h.snippet, ch) { jumpToHit(query, h) }
                })
            }
        }
    }

    private fun plain(html: String): String {
        val noScript = html.replace(Regex("<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        val noTags = noScript.replace(Regex("<[^>]+>"), " ")
        return noTags.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "’").replace("&lsquo;", "‘")
            .replace(Regex("\\s+"), " ")
    }

    private fun jumpToHit(query: String, h: Hit) {
        val v = html ?: return
        v.open(h.chapter, 0f)
        ui.postDelayed({ selectNth(v, query, h.chapter, h.ordinal, 0, 0, 0) }, 700)
    }

    /** Selects the [target]-th (0-based) match of [q] in the chapter, stepping from match to match. */
    private fun selectNth(v: HtmlBookView, q: String, chapter: Int, target: Int, count: Int, from: Int, retries: Int) {
        if (v.chapter != chapter) return
        v.find(q, from) { at ->
            when {
                at < 0 && retries < 8 && count == 0 -> ui.postDelayed({ selectNth(v, q, chapter, target, count, from, retries + 1) }, 300)   // chapter still loading
                at < 0 -> {}
                count >= target -> {}
                else -> selectNth(v, q, chapter, target, count + 1, at + 1, 0)
            }
        }
    }

    // ---- turning pages -------------------------------------------------------------------------------------------------------------------

    private fun turn(forward: Boolean) { if (sheet.isShowing) sheet.hide(); if (forward) backend?.next() else backend?.prev() }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN) Log.d("InkReader", "key ${ev.keyCode} ready=$ready sheet=${if (::sheet.isInitialized) sheet.isShowing else null}")
        if (!ready) return super.dispatchKeyEvent(ev)
        val code = ev.keyCode
        if (busy && code != KeyEvent.KEYCODE_BACK) return true
        if (code == KeyEvent.KEYCODE_BACK) {
            if (ev.action == KeyEvent.ACTION_UP) when {
                sheet.isShowing -> sheet.hide()
                studyPanel.visibility == View.VISIBLE -> hideStudy()
                contextBar.visibility == View.VISIBLE -> hideContext()
                else -> finish()
            }
            return true
        }
        val forward = code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE
        val backward = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP
        if (!forward && !backward) return super.dispatchKeyEvent(ev)
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0 && !sheet.isShowing && studyPanel.visibility != View.VISIBLE) turn(forward)
        return true
    }

    override fun onPause() {
        eink.restoreNow()
        if (ready) { ui.removeCallbacks(savePosition); savePositionNow() }
        super.onPause()
    }

    override fun onDestroy() {
        try { backend?.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }
}
