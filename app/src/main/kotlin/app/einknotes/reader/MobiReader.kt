package app.einknotes.reader

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Kindle books in the older MOBI / PalmDoc layout (.mobi, .prc, and the older part of many .azw files), without DRM.
 * The text is turned into html pages (split at the book's page breaks) with its pictures unpacked next to them.
 * The newer KF8 layout (.azw3) and protected books are not supported: convert those to EPUB first.
 */
object MobiReader {

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o) shl 16) or u16(b, o + 2)

    /** PalmDoc LZ77 decompression of one text record. */
    fun palmDecompress(data: ByteArray): ByteArray {
        var buf = ByteArray(data.size * 4 + 64); var n = 0
        fun put(c: Int) { if (n == buf.size) buf = buf.copyOf(buf.size * 2); buf[n++] = c.toByte() }
        var i = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            when {
                c == 0 || c in 9..0x7F -> put(c)
                c in 1..8 -> { var k = 0; while (k < c && i < data.size) { put(data[i++].toInt() and 0xFF); k++ } }
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= data.size) break
                    val pair = (c shl 8) or (data[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF; val len = (pair and 7) + 3
                    if (dist == 0 || dist > n) continue
                    for (k in 0 until len) put(buf[n - dist].toInt() and 0xFF)
                }
            }
        }
        return buf.copyOf(n)
    }

    /** Bytes at the end of a text record that are not text (see the MOBI "extra data flags"). */
    fun trailingSize(rec: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) {
                // a size written backwards, 7 bits per byte, the last byte read has its top bit set
                var bit = 0; var result = 0; var p = rec.size - num - 1
                while (p >= 0) {
                    val v = rec[p].toInt() and 0xFF
                    result = result or ((v and 0x7F) shl bit); bit += 7; p--
                    if (v and 0x80 != 0 || bit >= 28) break
                }
                num += result
            }
            f = f shr 1
        }
        if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 3) + 1
        return num.coerceAtMost(rec.size)
    }

    fun import(ctx: Context, id: String, file: File, baseName: String): Book {
        val data = file.readBytes()
        if (data.size < 100) throw ImportException("This file is too small to be a book")
        val recCount = u16(data, 76)
        val offsets = IntArray(recCount + 1) { if (it < recCount) u32(data, 78 + it * 8) else data.size }
        fun rec(i: Int): ByteArray = data.copyOfRange(offsets[i], offsets[i + 1].coerceAtLeast(offsets[i]))
        val r0 = rec(0)
        val compression = u16(r0, 0)
        val textRecords = u16(r0, 8)
        if (u16(r0, 12) != 0) throw ImportException("This Kindle book is protected (DRM) and cannot be opened here.")
        val hasMobi = r0.size > 32 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLen = if (hasMobi) u32(r0, 20) else 0
        val version = if (hasMobi) u32(r0, 36) else 0
        val encoding = if (hasMobi) u32(r0, 28) else 1252
        if (hasMobi && version >= 8) throw ImportException("This Kindle file uses the newer KF8 layout. Convert it to EPUB (for example with Calibre) and add that.")
        if (compression != 1 && compression != 2) throw ImportException("This Kindle file uses a compression that is not supported. Convert it to EPUB and add that.")
        val firstImage = if (hasMobi && r0.size > 112) u32(r0, 108) else -1
        val extraFlags = if (hasMobi && headerLen >= 228 && r0.size > 244) u16(r0, 242) else 0
        val charset = if (encoding == 65001) Charsets.UTF_8 else charset("windows-1252")

        val text = ByteArrayOutputStream()
        for (i in 1..minOf(textRecords, recCount - 1)) {
            var r = rec(i)
            r = r.copyOf(r.size - trailingSize(r, extraFlags))
            text.write(if (compression == 2) palmDecompress(r) else r)
        }
        val html = String(text.toByteArray(), charset)

        var title = baseName; var author = ""; var lang = ""; var coverIndex = -1
        if (hasMobi) {
            val nameOff = u32(r0, 84); val nameLen = u32(r0, 88)
            if (nameOff in 0 until r0.size && nameLen > 0 && nameOff + nameLen <= r0.size) title = String(r0, nameOff, nameLen, charset).ifBlank { baseName }
            val exth = 16 + headerLen
            if (r0.size > exth + 12 && String(r0, exth, 4, Charsets.ISO_8859_1) == "EXTH") {
                var p = exth + 12; val count = u32(r0, exth + 8)
                for (k in 0 until count) {
                    if (p + 8 > r0.size) break
                    val type = u32(r0, p); val len = u32(r0, p + 4)
                    if (len < 8 || p + len > r0.size) break
                    val v = r0.copyOfRange(p + 8, p + len)
                    when (type) {
                        100 -> if (author.isEmpty()) author = String(v, Charsets.UTF_8)
                        503 -> if (v.isNotEmpty()) title = String(v, Charsets.UTF_8)
                        524 -> lang = String(v, Charsets.UTF_8)
                        201 -> if (v.size >= 4) coverIndex = u32(v, 0)
                    }
                    p += len
                }
            }
        }

        val content = BookStore.content(ctx, id); content.mkdirs()
        File(content, "images").mkdirs()
        val imageNames = HashMap<Int, String>()
        if (firstImage in 1 until recCount) {
            for (i in firstImage until recCount) {
                val r = rec(i)
                val ext = when {
                    r.size > 4 && r[0] == 0xFF.toByte() && r[1] == 0xD8.toByte() -> "jpg"
                    r.size > 4 && r[0] == 0x89.toByte() && r[1] == 'P'.code.toByte() -> "png"
                    r.size > 4 && r[0] == 'G'.code.toByte() && r[1] == 'I'.code.toByte() -> "gif"
                    else -> null
                } ?: continue
                val name = "images/${i - firstImage + 1}.$ext"
                File(content, name).writeBytes(r); imageNames[i - firstImage + 1] = name
            }
        }
        var body = html.replace(Regex("<img\\b[^>]*?recindex=\"?0*(\\d+)\"?[^>]*>", RegexOption.IGNORE_CASE)) { m ->
            imageNames[m.groupValues[1].toInt()]?.let { "<img src=\"$it\">" } ?: ""
        }
        body = body.replace(Regex("<\\?xml[^>]*\\?>"), "").replace(Regex("</?(html|head|body)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<guide>.*?</guide>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<a\\b[^>]*?filepos=[^>]*>", RegexOption.IGNORE_CASE), "<a>")

        var chunks = body.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)).map { it.trim() }
            .filter { it.replace(Regex("<[^>]*>"), "").isNotBlank() || it.contains("<img", true) }
        if (chunks.size < 2 && body.length > 40_000) {
            val out = ArrayList<String>(); var start = 0
            while (start < body.length) {
                var end = minOf(body.length, start + 30_000)
                if (end < body.length) { val brk = body.indexOf("</p>", end, ignoreCase = true); end = if (brk >= 0 && brk - end < 4000) brk + 4 else end }
                out.add(body.substring(start, end)); start = end
            }
            chunks = out
        }
        if (chunks.isEmpty()) throw ImportException("This Kindle book has no readable text")
        val spine = ArrayList<String>(); val toc = ArrayList<TocEntry>()
        for ((i, c) in chunks.withIndex()) {
            val name = "ch$i.html"
            val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(c)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]*>"), "")?.replace(Regex("\\s+"), " ")?.trim()
            val t = heading?.takeIf { it.isNotEmpty() }?.take(90) ?: "Part ${i + 1}"
            File(content, name).writeText("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>${BookImporter.htmlEscape(t)}</title></head><body>$c</body></html>")
            spine.add(name); toc.add(TocEntry(t, name, 0))
        }
        val cover = imageNames[coverIndex + 1]
        return Book(id, title, author, "mobi", lang, System.currentTimeMillis(), spine, toc, cover, null, 0, spine.map { File(content, it).length() })
    }
}
