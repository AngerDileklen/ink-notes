package app.einknotes.notes

import android.content.Context
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The text of one PDF page with the box of every character, in notebook page units (origin top left). */
class PageText(val text: String, private val l: FloatArray, private val t: FloatArray, private val r: FloatArray, private val b: FloatArray) {
    val n get() = text.length

    /** Index of the character whose box is nearest to (x, y), or -1 when nothing is within [maxDist]. */
    fun charAt(x: Float, y: Float, maxDist: Float): Int {
        var best = -1; var bestD = maxDist
        for (i in 0 until n) {
            if (r[i] - l[i] <= 0f || b[i] - t[i] <= 0f || text[i].isWhitespace() && text[i] != ' ') continue
            val dx = if (x < l[i]) l[i] - x else if (x > r[i]) x - r[i] else 0f
            val dy = if (y < t[i]) t[i] - y else if (y > b[i]) y - b[i] else 0f
            val d = hypot(dx, dy)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** One rectangle per visual line for the characters from..to (inclusive). */
    fun lineRects(from: Int, to: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        var curMid = 0f
        for (i in max(0, from)..min(n - 1, to)) {
            if (r[i] - l[i] <= 0.5f || b[i] - t[i] <= 0.5f) continue
            val mid = (t[i] + b[i]) / 2f
            val h = b[i] - t[i]
            val c = cur
            if (c == null || abs(mid - curMid) > 0.6f * h || l[i] < c.right - 4f * h) {
                cur = RectF(l[i], t[i], r[i], b[i]); curMid = mid; out.add(cur)
            } else {
                c.left = min(c.left, l[i]); c.right = max(c.right, r[i]); c.top = min(c.top, t[i]); c.bottom = max(c.bottom, b[i])
                curMid = (c.top + c.bottom) / 2f
            }
        }
        return out
    }

    /** Index ranges of every (case-insensitive) occurrence of [query]. */
    fun find(query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var at = text.indexOf(q, 0, ignoreCase = true)
        while (at >= 0) { out.add(at until at + q.length); at = text.indexOf(q, at + q.length, ignoreCase = true) }
        return out
    }
}

class PdfOutlineEntry(val title: String, val level: Int, val pdfPage: Int)

class SearchHit(val pdfFile: String, val pdfPage: Int, val snippet: String)

/**
 * Text, search and outline of the PDFs in the library, using pdfium. Drawing stays with Android's PdfRenderer.
 * Every call is serialised (pdfium is not thread safe); text pages are cached in memory, and a plain-text copy of each
 * PDF is kept in notes/pdftext so searching a long book does not open it again.
 */
class PdfTextEngine(private val ctx: Context, private val pdfs: PdfLibrary) {
    private val lock = Any()
    private val core by lazy { PdfiumCore(ctx, Config()) }
    private var openName: String? = null
    private var doc: PdfDocument? = null
    private var pfd: ParcelFileDescriptor? = null
    private val cache = LruCache<String, PageText>(24)
    private val indexDir = File(ctx.filesDir, "notes/pdftext").also { it.mkdirs() }

    private fun open(name: String): PdfDocument? {
        if (openName == name && doc != null) return doc
        closeDoc()
        return try {
            val f = File(pdfs.dir, name)
            val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            val d = core.newDocument(p)
            pfd = p; doc = d; openName = name
            d
        } catch (t: Throwable) { Log.w("PdfText", "cannot open $name: $t"); null }
    }

    private fun closeDoc() {
        try { doc?.close() } catch (_: Throwable) { }
        try { pfd?.close() } catch (_: Throwable) { }
        doc = null; pfd = null; openName = null
    }

    fun close() = synchronized(lock) { closeDoc() }

    /** Text of page [idx] of PDF [name] (null if the page has no text layer). Cached. */
    fun pageText(name: String, idx: Int): PageText? = synchronized(lock) {
        val key = "$name#$idx"
        cache.get(key)?.let { return it }
        val d = open(name) ?: return null
        try {
            d.openPage(idx).use { page ->
                val widthPt = page.getPageWidthPoint().toFloat(); val heightPt = page.getPageHeightPoint().toFloat()
                if (widthPt <= 0f) return null
                val k = PAGE_W / widthPt
                d.openTextPage(page).use { tp ->
                    val count = tp.textPageCountChars()
                    if (count <= 0) return null
                    val text = tp.textPageGetText(0, count) ?: return null
                    val m = min(count, text.length)
                    val l = FloatArray(m); val t = FloatArray(m); val r = FloatArray(m); val b = FloatArray(m)
                    for (i in 0 until m) {
                        val box = tp.textPageGetCharBox(i) ?: continue
                        // pdfium boxes use a bottom-left origin: flip to the top-left origin of the notebook page
                        val top = min(box.top, box.bottom); val bottom = max(box.top, box.bottom)
                        l[i] = box.left * k; r[i] = box.right * k
                        t[i] = (heightPt - bottom) * k; b[i] = (heightPt - top) * k
                    }
                    val pt = PageText(text.substring(0, m), l, t, r, b)
                    cache.put(key, pt)
                    return pt
                }
            }
        } catch (e: Throwable) { Log.w("PdfText", "text of $name page $idx failed: $e"); null }
    }

    fun pageCount(name: String): Int = synchronized(lock) {
        val d = open(name)
        if (d == null) 0 else d.getPageCount()
    }

    /** The PDF's own table of contents, flattened with indentation levels. */
    fun outline(name: String): List<PdfOutlineEntry> = synchronized(lock) {
        val d = open(name) ?: return emptyList()
        val out = ArrayList<PdfOutlineEntry>()
        fun walk(list: List<PdfDocument.Bookmark>, level: Int) {
            for (bm in list) {
                out.add(PdfOutlineEntry(bm.title ?: "", level, bm.pageIdx.toInt()))
                walk(bm.children, level + 1)
            }
        }
        try { walk(d.getTableOfContents(), 0) } catch (e: Throwable) { Log.w("PdfText", "outline failed: $e") }
        out
    }

    // ---- highlighting along text ---------------------------------------------------------------------------------

    /**
     * A highlighter drag from (x0, y0) to (x1, y1) (page units) on a PDF page becomes highlights along the text between the
     * two points, one rectangle per line. Null when the drag did not start and end on text.
     */
    fun highlightRects(name: String, idx: Int, x0: Float, y0: Float, x1: Float, y1: Float): List<RectF>? {
        val pt = pageText(name, idx) ?: return null
        val a = pt.charAt(x0, y0, 36f); val b = pt.charAt(x1, y1, 36f)
        if (a < 0 || b < 0) return null
        return pt.lineRects(min(a, b), max(a, b)).takeIf { it.isNotEmpty() }
    }

    // ---- search --------------------------------------------------------------------------------------------------

    private fun indexFile(name: String) = File(indexDir, "$name.txt")

    /** Plain text of every page, page by page (built once per PDF, in the background by the caller). */
    fun ensureIndex(name: String, progress: (Int, Int) -> Unit): List<String> {
        val f = indexFile(name)
        if (f.exists()) return f.readText().split('\u000c')
        val n = pageCount(name)
        val pages = ArrayList<String>(n)
        for (i in 0 until n) {
            val tp = synchronized(lock) {
                try {
                    open(name)!!.openPage(i).use { p -> p.let { pg -> doc!!.openTextPage(pg).use { t ->
                        val c = t.textPageCountChars(); if (c > 0) t.textPageGetText(0, c) ?: "" else "" } } }
                } catch (_: Throwable) { "" }
            }
            pages.add(tp.replace('\u000c', ' '))
            if (i % 5 == 0) progress(i + 1, n)
        }
        try { f.writeText(pages.joinToString("\u000c")) } catch (_: Throwable) { }
        return pages
    }

    /** Pages of PDF [name] that contain [query], with a short piece of text around the first match on each. */
    fun search(name: String, pages: List<String>, query: String, limit: Int = 200): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>()
        for ((i, text) in pages.withIndex()) {
            val at = text.indexOf(q, 0, ignoreCase = true)
            if (at < 0) continue
            val from = max(0, at - 36); val to = min(text.length, at + q.length + 48)
            out.add(SearchHit(name, i, (if (from > 0) "…" else "") + text.substring(from, to).replace(Regex("\\s+"), " ") + (if (to < text.length) "…" else "")))
            if (out.size >= limit) break
        }
        return out
    }

    /** Rectangles of every match of [query] on page [idx], for showing where it is. */
    fun hitRects(name: String, idx: Int, query: String): List<RectF> {
        val pt = pageText(name, idx) ?: return emptyList()
        return pt.find(query).flatMap { pt.lineRects(it.first, it.last) }
    }

    fun clearIndex(name: String) { indexFile(name).delete() }

    /** Removes cached text of PDFs that are no longer in the library. */
    fun collectGarbage(keep: Set<String>) {
        indexDir.listFiles()?.forEach { if (it.name.removeSuffix(".txt") !in keep) it.delete() }
    }
}
