package app.einknotes.reader

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile

class ImportException(message: String) : Exception(message)

class ImportResult(val book: Book, val isNew: Boolean)

/**
 * Adds a book file to the reading library: copies it in, unpacks it, and writes a [Book] record.
 * EPUB, FB2, TXT and HTML become a list of html pages the reader shows. PDF and comic archives are kept as files.
 */
object BookImporter {

    val EXTENSIONS = setOf("epub", "fb2", "txt", "html", "htm", "xhtml", "mobi", "azw", "azw3", "prc", "pdf", "cbz")

    fun importBook(ctx: Context, uri: Uri, displayName: String?, progress: (String) -> Unit = {}): ImportResult {
        val tmp = File(ctx.cacheDir, "book-import-${System.nanoTime()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = ctx.contentResolver.openInputStream(uri) ?: throw ImportException("Cannot open that file")
            input.use { src -> tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n); digest.update(buf, 0, n) }
            } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val id = "bk_" + sha.take(16)
            BookStore.load(ctx, id)?.let { return ImportResult(it, false) }
            return ImportResult(importFile(ctx, id, tmp, displayName ?: "Book", progress), true)
        } finally { tmp.delete() }
    }

    /** [file] is a private copy that is moved into the library. */
    fun importFile(ctx: Context, id: String, file: File, displayName: String, progress: (String) -> Unit = {}): Book {
        val baseName = displayName.substringBeforeLast('.', displayName).ifBlank { "Book" }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val format = detectFormat(file, ext)
        val dir = BookStore.dir(ctx, id)
        dir.deleteRecursively(); dir.mkdirs()
        try {
            progress("Adding \"$baseName\"...")
            val book = when (format) {
                "epub" -> importEpub(ctx, id, file, baseName)
                "fb2" -> importFb2(ctx, id, file, baseName)
                "txt" -> importText(ctx, id, file, baseName)
                "html" -> importHtml(ctx, id, file, baseName)
                "mobi" -> MobiReader.import(ctx, id, file, baseName)
                "pdf" -> importPdf(ctx, id, file, baseName)
                "cbz" -> importCbz(ctx, id, file, baseName)
                else -> throw ImportException("This kind of file cannot be opened as a book")
            }
            file.copyTo(File(dir, "original." + (if (format == "html") "html" else format)), overwrite = true)
            BookStore.save(ctx, book)
            return book
        } catch (e: Throwable) {
            dir.deleteRecursively()
            if (e !is ImportException) android.util.Log.w("Books", "import failed", e)
            throw if (e is ImportException) e else ImportException("Could not read this book: ${e.message}")
        }
    }

    private fun detectFormat(f: File, ext: String): String {
        val head = ByteArray(2048)
        val n = FileInputStream(f).use { it.read(head) }
        val s = String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1)
        return when {
            s.startsWith("%PDF") -> "pdf"
            n > 68 && (String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI" || String(head, 60, 8, Charsets.ISO_8859_1) == "TEXtREAd") -> "mobi"
            s.startsWith("PK") -> try {
                ZipFile(f).use { z -> if (z.getEntry("META-INF/container.xml") != null) "epub" else if (ext == "cbz" || z.entries().asSequence().any { it.name.lowercase().let { n2 -> n2.endsWith(".jpg") || n2.endsWith(".png") || n2.endsWith(".jpeg") } }) "cbz" else "unknown" }
            } catch (_: Throwable) { "unknown" }
            s.contains("<FictionBook") -> "fb2"
            ext in setOf("html", "htm", "xhtml") || s.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true) || s.trimStart().startsWith("<html", ignoreCase = true) -> "html"
            ext == "txt" || ext == "text" -> "txt"
            ext == "mobi" || ext == "azw" || ext == "azw3" || ext == "prc" -> throw ImportException("This Kindle file could not be read (it may be protected or use a newer Kindle format). Convert it to EPUB and add that.")
            else -> "unknown"
        }
    }

    // ---- shared helpers ---------------------------------------------------------------------------------------------

    fun unzip(zip: File, dest: File, only: ((String) -> Boolean)? = null) {
        val root = dest.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                if (only != null && !only(e.name)) continue
                val out = File(dest, e.name).canonicalFile
                if (!out.path.startsWith(root)) continue                    // never write outside the folder
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    fun resolve(baseDir: String, href: String): String {
        val h = try { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (_: Throwable) { href }
        val parts = ArrayList<String>()
        for (seg in (if (baseDir.isEmpty() || h.startsWith("/")) h.trimStart('/') else "$baseDir/$h").split('/')) {
            when (seg) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(seg) }
        }
        return parts.joinToString("/")
    }

    private fun parser(f: File): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(FileInputStream(f), null)
        return p
    }

    private fun local(name: String?) = (name ?: "").substringAfter(':').lowercase()

    fun htmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun writePage(dir: File, name: String, title: String, body: String) {
        File(dir, name).writeText("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${htmlEscape(title)}</title></head><body>$body</body></html>")
    }

    // ---- EPUB -------------------------------------------------------------------------------------------------------

    private class ManifestItem(val href: String, val type: String, val props: String)

    private fun importEpub(ctx: Context, id: String, file: File, baseName: String): Book {
        val content = BookStore.content(ctx, id); content.mkdirs()
        unzip(file, content)
        File(content, "META-INF/encryption.xml").takeIf { it.exists() }?.let { enc ->
            val txt = enc.readText()
            val bad = Regex("""Algorithm="([^"]+)"""").findAll(txt).map { it.groupValues[1] }.any { !it.contains("idpf.org/2008/embedding") && !it.contains("ns.adobe.com/pdf/enc#RC") }
            if (bad) throw ImportException("This book is protected (DRM) and cannot be opened here.")
        }
        val container = File(content, "META-INF/container.xml")
        var opfPath = ""
        parser(container).let { p -> while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "rootfile") { opfPath = p.getAttributeValue(null, "full-path") ?: ""; break } }
        if (opfPath.isEmpty() || !File(content, opfPath).exists()) throw ImportException("This EPUB has no package file")
        val opfDir = opfPath.substringBeforeLast('/', "")

        var title = ""; var author = ""; var lang = ""
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spineIds = ArrayList<String>()
        var tocId: String? = null; var coverId: String? = null
        parser(File(content, opfPath)).let { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (local(p.name)) {
                    "title" -> if (title.isEmpty()) title = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "creator" -> if (author.isEmpty()) author = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "language" -> if (lang.isEmpty()) lang = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "meta" -> if (p.getAttributeValue(null, "name") == "cover") coverId = p.getAttributeValue(null, "content")
                    "item" -> manifest[p.getAttributeValue(null, "id") ?: ""] = ManifestItem(
                        resolve(opfDir, p.getAttributeValue(null, "href") ?: ""), p.getAttributeValue(null, "media-type") ?: "", p.getAttributeValue(null, "properties") ?: "")
                    "spine" -> tocId = p.getAttributeValue(null, "toc")
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                }
            }
        }
        val spine = spineIds.mapNotNull { manifest[it] }
            .filter { it.type.contains("html") || it.href.lowercase().let { h -> h.endsWith(".xhtml") || h.endsWith(".html") || h.endsWith(".htm") } }
            .map { it.href }.filter { File(content, it).exists() }
        if (spine.isEmpty()) throw ImportException("This EPUB has no readable pages")

        // table of contents: EPUB 3 navigation document, else the NCX
        var toc: List<TocEntry> = emptyList()
        manifest.values.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav ->
            toc = try { parseNav(File(content, nav.href), nav.href.substringBeforeLast('/', "")) } catch (_: Throwable) { emptyList() }
        }
        if (toc.isEmpty()) {
            val ncx = (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.type.contains("ncx") })
            if (ncx != null) toc = try { parseNcx(File(content, ncx.href), ncx.href.substringBeforeLast('/', "")) } catch (_: Throwable) { emptyList() }
        }
        toc = toc.filter { File(content, it.href.substringBefore('#')).exists() }
        if (toc.isEmpty()) toc = spine.mapIndexed { i, h -> TocEntry("Section ${i + 1}", h, 0) }

        // cover
        var cover: String? = coverId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { it.props.split(' ').contains("cover-image") }?.href
            ?: manifest.entries.firstOrNull { it.value.type.startsWith("image") && (it.key.lowercase().contains("cover") || it.value.href.lowercase().contains("cover")) }?.value?.href
        if (cover != null && !File(content, cover).exists()) cover = null
        return Book(id, title.ifBlank { baseName }, author, "epub", lang, System.currentTimeMillis(), spine, toc, cover, null, 0, spine.map { File(content, it).length() })
    }

    private fun parseNcx(f: File, base: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        val p = parser(f)
        var depth = 0; var inLabel = false
        val pendingTitles = ArrayList<String?>()
        var curTitle = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "navpoint" -> { depth++; pendingTitles.add(null) }
                    "navlabel" -> inLabel = true
                    "text" -> if (inLabel) { curTitle = (try { p.nextText() } catch (_: Throwable) { "" }).trim(); if (pendingTitles.isNotEmpty() && pendingTitles.last() == null) pendingTitles[pendingTitles.size - 1] = curTitle }
                    "content" -> { val src = p.getAttributeValue(null, "src"); val t = pendingTitles.lastOrNull(); if (src != null && t != null) out.add(TocEntry(t, resolve(base, src), depth - 1)) }
                }
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "navlabel" -> inLabel = false
                    "navpoint" -> { depth--; if (pendingTitles.isNotEmpty()) pendingTitles.removeAt(pendingTitles.size - 1) }
                }
            }
        }
        return out
    }

    private fun parseNav(f: File, base: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        val p = parser(f)
        var inToc = false; var navDepth = 0; var listDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "nav" -> { navDepth++; val t = (0 until p.attributeCount).firstOrNull { local(p.getAttributeName(it)) == "type" }?.let { p.getAttributeValue(it) } ?: ""; if (t.split(' ').contains("toc")) inToc = true }
                    "ol" -> if (inToc) listDepth++
                    "a" -> if (inToc) {
                        val href = p.getAttributeValue(null, "href")
                        val sb = StringBuilder(); var d = 1
                        while (d > 0) { val e = p.next(); if (e == XmlPullParser.END_DOCUMENT) break; if (e == XmlPullParser.START_TAG) d++ else if (e == XmlPullParser.END_TAG) d-- else if (e == XmlPullParser.TEXT) sb.append(p.text) }
                        if (href != null) out.add(TocEntry(sb.toString().replace(Regex("\\s+"), " ").trim(), resolve(base, href), (listDepth - 1).coerceAtLeast(0)))
                    }
                }
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "ol" -> if (inToc) listDepth--
                    "nav" -> { navDepth--; if (inToc && navDepth == 0) inToc = false else if (inToc) inToc = false }
                }
            }
        }
        return out.filter { it.title.isNotEmpty() }
    }

    // ---- plain text -------------------------------------------------------------------------------------------------

    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { }
        // old Turkish files use Windows-1254; its letters i-dotless, s-cedilla and g-breve sit where Windows-1252 has other symbols
        val turkish = bytes.count { it == 0xFD.toByte() || it == 0xDD.toByte() || it == 0xFE.toByte() || it == 0xDE.toByte() || it == 0xF0.toByte() || it == 0xD0.toByte() }
        return String(bytes, charset(if (turkish > bytes.size / 400) "windows-1254" else "windows-1252"))
    }

    class Chunk(val title: String, val html: String)

    /** Splits plain text into readable chapters (at headings like "Chapter 3", else every ~9000 characters). */
    fun splitText(text: String): List<Chunk> {
        val heading = Regex("^\\s*(chapter|bölüm|kısım|part|book|section|prologue|epilogue|giriş|önsöz)\\b.{0,70}$", RegexOption.IGNORE_CASE)
        val paras = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Chunk>()
        var title = ""; var sb = StringBuilder(); var size = 0
        fun flush() { if (sb.isNotEmpty()) { out.add(Chunk(title.ifBlank { "Part ${out.size + 1}" }, sb.toString())); sb = StringBuilder(); size = 0; title = "" } }
        for (para in paras) {
            val lines = para.split('\n')
            val isHeading = lines.size == 1 && heading.matches(para)
            if (isHeading && size > 1500) flush()
            if (isHeading && title.isEmpty()) title = para.take(80)
            else if (size > 9000) flush()
            // single line breaks inside a paragraph: hard-wrapped text is joined, short lines (poems, lists) are kept
            val body = if (lines.all { it.length > 45 || it === lines.last() }) lines.joinToString(" ") { it.trim() } else lines.joinToString("<br>") { htmlEscape(it.trim()) }
            sb.append(if (isHeading) "<h2>${htmlEscape(para)}</h2>" else "<p>" + (if (lines.all { it.length > 45 || it === lines.last() }) htmlEscape(body) else body) + "</p>")
            size += para.length
        }
        flush()
        return out.ifEmpty { listOf(Chunk("Text", "<p>(empty)</p>")) }
    }

    private fun importText(ctx: Context, id: String, file: File, baseName: String): Book {
        val content = BookStore.content(ctx, id); content.mkdirs()
        val chunks = splitText(decodeText(file.readBytes()))
        val spine = ArrayList<String>(); val toc = ArrayList<TocEntry>()
        for ((i, c) in chunks.withIndex()) { val name = "ch$i.html"; writePage(content, name, c.title, c.html); spine.add(name); toc.add(TocEntry(c.title, name, 0)) }
        return Book(id, baseName, "", "txt", "", System.currentTimeMillis(), spine, toc, null, null, 0, spine.map { File(content, it).length() })
    }

    private fun importHtml(ctx: Context, id: String, file: File, baseName: String): Book {
        val content = BookStore.content(ctx, id); content.mkdirs()
        file.copyTo(File(content, "ch0.html"), overwrite = true)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(File(content, "ch0.html").readText())?.groupValues?.get(1)?.trim()
        return Book(id, title?.takeIf { it.isNotEmpty() } ?: baseName, "", "html", "", System.currentTimeMillis(), listOf("ch0.html"), listOf(TocEntry(baseName, "ch0.html", 0)), null, null, 0, listOf(file.length()))
    }

    // ---- FB2 --------------------------------------------------------------------------------------------------------

    private fun importFb2(ctx: Context, id: String, file: File, baseName: String): Book {
        val content = BookStore.content(ctx, id); content.mkdirs()
        File(content, "images").mkdirs()
        // pass 1: pictures (binary elements) and description
        var title = ""; var author = ""; var lang = ""; var coverRef: String? = null
        val images = HashMap<String, String>()          // id -> file name
        run {
            val p = parser(file)
            var inTitleInfo = false; var first = ""; var last = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) when (local(p.name)) {
                    "title-info" -> inTitleInfo = true
                    "book-title" -> if (inTitleInfo && title.isEmpty()) title = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "first-name" -> if (inTitleInfo && author.isEmpty()) first = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "last-name" -> if (inTitleInfo && author.isEmpty()) { last = (try { p.nextText() } catch (_: Throwable) { "" }).trim(); author = "$first $last".trim() }
                    "lang" -> if (inTitleInfo && lang.isEmpty()) lang = (try { p.nextText() } catch (_: Throwable) { "" }).trim()
                    "image" -> if (inTitleInfo && coverRef == null) coverRef = (0 until p.attributeCount).firstOrNull { local(p.getAttributeName(it)) == "href" }?.let { p.getAttributeValue(it).trimStart('#') }
                    "binary" -> {
                        val bid = p.getAttributeValue(null, "id") ?: ""; val type = p.getAttributeValue(null, "content-type") ?: ""
                        val ext = if (type.contains("png")) "png" else if (type.contains("gif")) "gif" else "jpg"
                        val data = try { p.nextText() } catch (_: Throwable) { "" }
                        if (bid.isNotEmpty() && data.isNotEmpty()) try {
                            val name = "images/${bid.replace(Regex("[^A-Za-z0-9._-]"), "_")}.$ext"
                            File(content, name).writeBytes(Base64.decode(data.trim(), Base64.DEFAULT)); images[bid] = name
                        } catch (_: Throwable) { }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && local(p.name) == "title-info") inTitleInfo = false
            }
        }
        // pass 2: text
        val chapters = ArrayList<Chunk>()
        run {
            val p = parser(file)
            var inBody = false; var skipBody = false; var sectionDepth = 0; var inBinary = false
            var sb = StringBuilder(); var chTitle = ""; var inTitle = false; val titleBuf = StringBuilder(); var titleLevel = 2
            fun flush() { if (sb.isNotBlank()) { chapters.add(Chunk(chTitle.ifBlank { "Part ${chapters.size + 1}" }, sb.toString())) }; sb = StringBuilder(); chTitle = "" }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        val n = local(p.name)
                        when (n) {
                            "body" -> { inBody = true; skipBody = p.getAttributeValue(null, "name") == "notes" }
                            "binary" -> inBinary = true
                            "section" -> if (inBody && !skipBody) { sectionDepth++; if (sectionDepth == 1) flush() }
                            "title" -> if (inBody && !skipBody) { inTitle = true; titleBuf.setLength(0); titleLevel = (sectionDepth + 1).coerceIn(2, 4); sb.append("<h$titleLevel>") }
                            "subtitle" -> if (inBody) sb.append("<h4>")
                            "p" -> if (inBody && !skipBody) sb.append("<p>")
                            "emphasis" -> if (inBody) sb.append("<em>")
                            "strong" -> if (inBody) sb.append("<strong>")
                            "strikethrough" -> if (inBody) sb.append("<s>")
                            "sub" -> if (inBody) sb.append("<sub>")
                            "sup" -> if (inBody) sb.append("<sup>")
                            "empty-line" -> if (inBody) sb.append("<br>")
                            "epigraph", "cite", "poem" -> if (inBody) sb.append("<blockquote>")
                            "v" -> if (inBody) sb.append("<p class=\"v\">")
                            "text-author" -> if (inBody) sb.append("<p><i>")
                            "image" -> if (inBody && !skipBody) {
                                val ref = (0 until p.attributeCount).firstOrNull { local(p.getAttributeName(it)) == "href" }?.let { p.getAttributeValue(it).trimStart('#') }
                                images[ref]?.let { sb.append("<p style=\"text-align:center\"><img src=\"$it\"></p>") }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inBody && !skipBody && !inBinary) { val t = htmlEscape(p.text ?: ""); sb.append(t); if (inTitle) titleBuf.append(p.text) }
                    XmlPullParser.END_TAG -> {
                        val n = local(p.name)
                        when (n) {
                            "body" -> { flush(); inBody = false }
                            "binary" -> inBinary = false
                            "section" -> if (inBody && !skipBody) { if (sectionDepth == 1) flush(); sectionDepth-- }
                            "title" -> if (inBody && !skipBody) { inTitle = false; sb.append("</h$titleLevel>"); if (chTitle.isEmpty()) chTitle = titleBuf.toString().replace(Regex("\\s+"), " ").trim().take(90) }
                            "subtitle" -> if (inBody) sb.append("</h4>")
                            "p", "v" -> if (inBody && !skipBody) sb.append("</p>")
                            "emphasis" -> if (inBody) sb.append("</em>")
                            "strong" -> if (inBody) sb.append("</strong>")
                            "strikethrough" -> if (inBody) sb.append("</s>")
                            "sub" -> if (inBody) sb.append("</sub>")
                            "sup" -> if (inBody) sb.append("</sup>")
                            "epigraph", "cite", "poem" -> if (inBody) sb.append("</blockquote>")
                            "text-author" -> if (inBody) sb.append("</i></p>")
                        }
                    }
                }
            }
        }
        if (chapters.isEmpty()) throw ImportException("This FB2 file has no text")
        val spine = ArrayList<String>(); val toc = ArrayList<TocEntry>()
        for ((i, c) in chapters.withIndex()) { val name = "ch$i.html"; writePage(content, name, c.title, c.html); spine.add(name); toc.add(TocEntry(c.title, name, 0)) }
        val cover = coverRef?.let { images[it] }
        return Book(id, title.ifBlank { baseName }, author, "fb2", lang, System.currentTimeMillis(), spine, toc, cover, null, 0, spine.map { File(content, it).length() })
    }

    // ---- PDF and comics ---------------------------------------------------------------------------------------------

    private fun importPdf(ctx: Context, id: String, file: File, baseName: String): Book {
        val pdfs = app.einknotes.notes.PdfLibrary(ctx)
        val stored = pdfs.importFrom(ctx, Uri.fromFile(file))
        val count = pdfs.pageCount(stored)
        // cover: the first page
        var cover: String? = null
        try {
            val (wp, hp) = pdfs.pageSize(stored, 0)
            val w = 360; val h = (w * hp / maxOf(1, wp).toFloat()).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            if (pdfs.render(stored, 0, bmp, app.einknotes.notes.PAGE_W, w / app.einknotes.notes.PAGE_W, 0f, 0f)) {
                val content = BookStore.content(ctx, id); content.mkdirs()
                File(content, "cover.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                cover = "cover.png"
            }
            bmp.recycle()
        } catch (_: Throwable) { }
        return Book(id, baseName, "", "pdf", "", System.currentTimeMillis(), emptyList(), emptyList(), cover, stored, count)
    }

    private fun importCbz(ctx: Context, id: String, file: File, baseName: String): Book {
        val content = BookStore.content(ctx, id); content.mkdirs()
        val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif")
        unzip(file, content) { it.substringAfterLast('.', "").lowercase() in imageExt }
        val pages = (content.walkTopDown().filter { it.isFile }.map { it.relativeTo(content).path }.sorted().toList())
        if (pages.isEmpty()) throw ImportException("This comic archive has no pictures")
        return Book(id, baseName, "", "cbz", "", System.currentTimeMillis(), pages, emptyList(), pages.first(), null, pages.size)
    }
}
