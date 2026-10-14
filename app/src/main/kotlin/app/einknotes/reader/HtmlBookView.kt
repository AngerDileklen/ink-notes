package app.einknotes.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Shows a book made of html pages (EPUB, FB2, text, HTML, Kindle) one screen at a time. Each chapter is laid out in
 * columns by reader.js and the screens are stepped through with a jump, never a scroll. Book scripts and network
 * access are blocked; a tap on a word, a drag over text and a tap on a link are reported to [Host].
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class HtmlBookView(context: Context, private val book: Book, private val contentDir: File, private val host: Host) : FrameLayout(context), ReaderBackend {

    interface Host {
        fun onWord(word: String, sentence: String, start: Int, end: Int, highlightId: String)
        fun onSelection(text: String, start: Int, end: Int)
        fun onBlankTap()
        fun onPosition()
        fun highlightsFor(chapter: Int): List<Highlight>
    }

    private sealed class Nav {
        class Page(val n: Int) : Nav()
        object End : Nav()
        class Fraction(val f: Float) : Nav()
        class Anchor(val id: String) : Nav()
        class Offset(val o: Int) : Nav()
    }

    private val debugLog = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun debugState() = "chapter=$chapter page=$page pages=$pages ready=$ready"

    var chapter = 0; private set
    var page = 0; private set
    var pages = 1; private set
    var fontPx = 17; var family = "serif"; var lineHeight = 1.5f; var marginPx = 22

    private var pending: Nav = Nav.Page(0)
    private var ready = false
    private val web = WebView(context)
    private val root: String = contentDir.canonicalPath
    private val readerJs: String by lazy { context.assets.open("reader/reader.js").bufferedReader().readText() }

    init {
        with(web.settings) {
            javaScriptEnabled = true; allowFileAccess = true; allowContentAccess = false; blockNetworkLoads = true
            setSupportZoom(false); builtInZoomControls = false; domStorageEnabled = false; textZoom = 100
            useWideViewPort = false; loadWithOverviewMode = false; mediaPlaybackRequiresUserGesture = true
        }
        web.setBackgroundColor(Color.WHITE)
        web.isVerticalScrollBarEnabled = false; web.isHorizontalScrollBarEnabled = false; web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false; web.setOnLongClickListener { true }; web.isHapticFeedbackEnabled = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.scheme != "file") return blocked()
                val f = try { File(u.path ?: return blocked()).canonicalFile } catch (_: Throwable) { return blocked() }
                if (!f.path.startsWith(root)) return blocked()
                val ext = f.extension.lowercase()
                if (ext == "html" || ext == "xhtml" || ext == "htm") {
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(prepare(f).toByteArray(Charsets.UTF_8)))
                }
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        addView(web, LayoutParams(-1, -1))
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 404, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private val voidTags = "area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr"
    private val selfClosing = Regex("<(?!(?:$voidTags)\\b)([a-zA-Z][\\w:-]*)((?:\\s[^<>]*?)?)\\s*/>")
    private val scripts = Regex("<script\\b[^>]*>.*?</script\\s*>|<script\\b[^>]*/>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val handlers = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE)

    /** The page as the reader shows it: no scripts, valid html, and reader.js added. */
    private fun prepare(f: File): String {
        var html = BookImporter.decodeText(f.readBytes())
        html = html.replace(scripts, "").replace(handlers, "").replace("href=\"javascript:", "href=\"#").replace(Regex("<\\?xml[^>]*\\?>"), "")
        html = html.replace(selfClosing, "<$1$2></$1>")
        val inject = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no\"><script>$readerJs</script>"
        val head = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.substring(0, head.range.last + 1) + inject + html.substring(head.range.last + 1)
        else {
            val h = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (h != null) html.substring(0, h.range.last + 1) + "<head>$inject</head>" + html.substring(h.range.last + 1) else "<html><head>$inject</head><body>$html</body></html>"
        }
    }

    // ---- navigation -------------------------------------------------------------------------------------------------

    fun open(chapter: Int, fraction: Float) = openChapter(chapter, Nav.Fraction(fraction))

    private fun openChapter(index: Int, nav: Nav) {
        chapter = index.coerceIn(0, book.spine.size - 1)
        pending = nav; ready = false
        web.loadUrl(Uri.fromFile(File(contentDir, book.spine[chapter])).toString())
    }

    /** Goes to a table-of-contents or link target ("path#fragment", relative to the content folder). */
    fun openHref(href: String): Boolean {
        val path = href.substringBefore('#'); val frag = href.substringAfter('#', "")
        val idx = if (path.isEmpty()) chapter else book.spine.indexOf(path)
        if (idx < 0) return false
        if (idx == chapter && ready) { if (frag.isEmpty()) gotoPage(0) else evalInt("ink.pageOfElement(${quote(frag)})") { gotoPage(it) } }
        else openChapter(idx, if (frag.isEmpty()) Nav.Page(0) else Nav.Anchor(frag))
        return true
    }

    fun openAtOffset(ch: Int, offset: Int) { if (ch == chapter && ready) evalInt("ink.pageOfOffset($offset)") { gotoPage(it) } else openChapter(ch, Nav.Offset(offset)) }

    override val view: View get() = this

    override fun label() = "Chapter ${chapter + 1} of ${book.spine.size}  ·  page ${page + 1}/$pages"

    override fun percent(): Float {
        val sizes = book.spineSizes
        val total = sizes.sum()
        if (sizes.size != book.spine.size || total <= 0L) return (chapter + fraction) / maxOf(1, book.spine.size)
        return ((sizes.take(chapter).sum() + sizes[chapter] * fraction) / total).coerceIn(0f, 1f)
    }

    override fun chapterTitle(): String {
        val path = book.spine[chapter]
        return book.toc.lastOrNull { it.href.substringBefore('#') == path }?.title ?: "Section ${chapter + 1}"
    }

    override fun savePosition(state: ReadingState) { state.chapter = chapter; state.fraction = fraction }

    override fun next() { if (!ready) return; if (page + 1 < pages) gotoPage(page + 1) else if (chapter + 1 < book.spine.size) openChapter(chapter + 1, Nav.Page(0)) }
    override fun prev() { if (!ready) return; if (page > 0) gotoPage(page - 1) else if (chapter > 0) openChapter(chapter - 1, Nav.End) }

    private fun gotoPage(p: Int) {
        page = p.coerceIn(0, maxOf(0, pages - 1))
        web.evaluateJavascript("ink.goto($page)", null)
        host.onPosition()
    }

    /** After a change of font, margins or window size: lay the chapter out again and stay about where we were. */
    override fun relayout() {
        if (!ready) return
        pending = Nav.Fraction(page / maxOf(1f, pages.toFloat()))
        layout()
    }

    val fraction: Float get() = page / maxOf(1f, pages.toFloat())

    private fun layout() {
        web.evaluateJavascript("ink.layout($fontPx, '$family', $lineHeight, $marginPx)") { r ->
            pages = r?.trim('"')?.toIntOrNull() ?: 1
            ready = true
            val done = { p: Int -> gotoPage(p); applyHighlights() }
            when (val n = pending) {
                is Nav.Page -> done(n.n)
                is Nav.End -> done(pages - 1)
                is Nav.Fraction -> done(Math.round(n.f * pages).coerceIn(0, pages - 1).let { if (n.f >= 0.999f) pages - 1 else it })
                is Nav.Anchor -> evalInt("ink.pageOfElement(${quote(n.id)})") { done(it) }
                is Nav.Offset -> evalInt("ink.pageOfOffset(${n.o})") { done(it) }
            }
        }
    }

    // ---- highlights, selection, text ------------------------------------------------------------------------------------

    fun applyHighlights() {
        val arr = JSONArray()
        for (h in host.highlightsFor(chapter)) arr.put(JSONObject().put("id", h.time).put("s", h.start).put("e", h.end))
        web.evaluateJavascript("ink.applyHighlights($arr)", null)
    }

    fun addHighlight(start: Int, end: Int, id: Long) { web.evaluateJavascript("ink.addHighlight($start,$end,'$id');ink.clearSelection()", null) }
    fun removeHighlight(id: String) { web.evaluateJavascript("ink.removeHighlight(${quote(id)})", null) }
    fun clearSelection() { web.evaluateJavascript("ink.clearSelection()", null) }

    fun chapterText(done: (String) -> Unit) {
        web.evaluateJavascript("ink.chapterText()") { r -> done(try { JSONTokener(r).nextValue() as? String ?: "" } catch (_: Throwable) { "" }) }
    }

    /** Looks for [query] from [from] on in this chapter, selecting the first match; reports where it was (or -1). */
    fun find(query: String, from: Int, done: (Int) -> Unit) {
        evalInt("ink.find(${quote(query)}, $from)") { at -> if (at >= 0) evalInt("ink.pageOfOffset($at)") { gotoPage(it); done(at) } else done(-1) }
    }

    private fun quote(s: String) = JSONObject.quote(s)

    private fun evalInt(js: String, cb: (Int) -> Unit) { web.evaluateJavascript(js) { r -> cb(r?.trim('"')?.toDoubleOrNull()?.toInt() ?: 0) } }

    override fun destroy() { web.removeJavascriptInterface("Android"); web.destroy() }

    private inner class Bridge {
        @JavascriptInterface fun log(m: String) { if (debugLog) Log.d("InkReader", m) }
        @JavascriptInterface fun onLoaded() { post { layout() } }
        @JavascriptInterface fun onTap(zone: String) { post { when (zone) { "left" -> prev(); "right" -> next(); else -> host.onBlankTap() } } }
        @JavascriptInterface fun onWord(word: String, sentence: String, start: Int, end: Int, hl: String) { post { host.onWord(word, sentence, start, end, hl) } }
        @JavascriptInterface fun onSelection(text: String, start: Int, end: Int) { post { host.onSelection(text, start, end) } }
        @JavascriptInterface fun onLink(href: String) {
            post {
                if (href.startsWith("http:") || href.startsWith("https:") || href.startsWith("mailto:")) return@post
                val base = book.spine[chapter].substringBeforeLast('/', "")
                openHref(BookImporter.resolve(base, href.substringBefore('#')).let { if (href.startsWith("#")) "" else it } + (if (href.contains('#')) "#" + href.substringAfter('#') else ""))
            }
        }
    }
}
