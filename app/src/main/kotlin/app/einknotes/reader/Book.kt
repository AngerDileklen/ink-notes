package app.einknotes.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One chapter-like entry of the table of contents. [href] is relative to the book's content folder and may end in #fragment. */
class TocEntry(val title: String, val href: String, val level: Int)

/**
 * A book in the reading library. Everything the reader needs is unpacked under files/books/<id>/ :
 *   book.json   this record        content/   the unpacked pages (html books)        original.<ext>   the file as it was added
 *   state.json  reading position, highlights and the word list
 */
class Book(
    val id: String,
    var title: String,
    var author: String,
    /** epub, fb2, txt, html, mobi, pdf or cbz */
    val format: String,
    var language: String,
    val addedAt: Long,
    /** html pages in reading order, relative to content/ (html books only) */
    val spine: List<String>,
    val toc: List<TocEntry>,
    /** cover picture relative to content/, or null */
    var cover: String?,
    /** pdf and cbz: stored file name; page count */
    var file: String? = null,
    var pageCount: Int = 0,
    /** size in bytes of every spine page (to estimate progress through the whole book) */
    val spineSizes: List<Long> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author).put("format", format)
        .put("language", language).put("addedAt", addedAt).put("spine", JSONArray(spine)).put("cover", cover ?: JSONObject.NULL)
        .put("file", file ?: JSONObject.NULL).put("pageCount", pageCount).put("spineSizes", JSONArray(spineSizes))
        .put("toc", JSONArray().also { a -> for (t in toc) a.put(JSONObject().put("t", t.title).put("h", t.href).put("l", t.level)) })

    companion object {
        fun fromJson(o: JSONObject): Book {
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            val toc = o.optJSONArray("toc")?.let { a -> (0 until a.length()).map { val t = a.getJSONObject(it); TocEntry(t.getString("t"), t.getString("h"), t.optInt("l")) } } ?: emptyList()
            val sizes = o.optJSONArray("spineSizes")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
            return Book(o.getString("id"), o.optString("title"), o.optString("author"), o.optString("format"), o.optString("language"),
                o.optLong("addedAt"), strs(o.optJSONArray("spine")), toc, o.optString("cover").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("file").takeIf { it.isNotEmpty() && it != "null" }, o.optInt("pageCount"), sizes)
        }
    }
}

class Highlight(val chapter: Int, val start: Int, val end: Int, val text: String, val time: Long, val page: Int = -1)

/** Where the person is in a book, what they highlighted, and the words they saved. Saved next to the book. */
class ReadingState(
    var chapter: Int = 0,
    /** position inside the chapter, 0..1 (so it survives a change of font size) */
    var fraction: Float = 0f,
    /** pdf and cbz: current page, and the scroll position within it */
    var page: Int = 0,
    var scroll: Float = 0f,
    val highlights: MutableList<Highlight> = ArrayList()
) {
    fun toJson(): JSONObject = JSONObject().put("chapter", chapter).put("fraction", fraction.toDouble()).put("page", page).put("scroll", scroll.toDouble())
        .put("highlights", JSONArray().also { a -> for (h in highlights) a.put(JSONObject().put("c", h.chapter).put("s", h.start).put("e", h.end).put("t", h.text).put("time", h.time).put("p", h.page)) })

    companion object {
        fun fromJson(o: JSONObject): ReadingState {
            val s = ReadingState(o.optInt("chapter"), o.optDouble("fraction").toFloat(), o.optInt("page"), o.optDouble("scroll").toFloat())
            o.optJSONArray("highlights")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { h ->
                s.highlights.add(Highlight(h.optInt("c"), h.optInt("s"), h.optInt("e"), h.optString("t"), h.optLong("time"), h.optInt("p", -1))) } }
            return s
        }
    }
}

object BookStore {
    fun root(ctx: Context) = File(ctx.filesDir, "books").also { it.mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)
    fun content(ctx: Context, id: String) = File(dir(ctx, id), "content")

    fun list(ctx: Context): List<Book> =
        (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(ctx, it.name) }.sortedByDescending { it.addedAt }

    fun load(ctx: Context, id: String): Book? = try { Book.fromJson(JSONObject(File(dir(ctx, id), "book.json").readText())) } catch (_: Throwable) { null }

    fun save(ctx: Context, b: Book) = writeAtomic(File(dir(ctx, b.id), "book.json"), b.toJson().toString())

    fun loadState(ctx: Context, id: String): ReadingState =
        try { ReadingState.fromJson(JSONObject(File(dir(ctx, id), "state.json").readText())) } catch (_: Throwable) { ReadingState() }

    fun saveState(ctx: Context, id: String, s: ReadingState) = writeAtomic(File(dir(ctx, id), "state.json"), s.toJson().toString())

    fun remove(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }

    fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
