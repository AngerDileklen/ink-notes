package app.einknotes.notes

import app.einknotes.reader.BookImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportTest {
    @Test fun resolvesRelativePaths() {
        assertEquals("OEBPS/ch1.xhtml", BookImporter.resolve("OEBPS", "ch1.xhtml"))
        assertEquals("OEBPS/images/a b.png", BookImporter.resolve("OEBPS/text", "../images/a%20b.png"))
        assertEquals("ch1.xhtml", BookImporter.resolve("", "./ch1.xhtml"))
        assertEquals("a/b.html", BookImporter.resolve("x/y", "/a/b.html"))
    }

    @Test fun decodesTextFiles() {
        assertEquals("Çocuk ğüşiö", BookImporter.decodeText("Çocuk ğüşiö".toByteArray(Charsets.UTF_8)))
        val old = "Güneş ışığı çok güzel, İstanbul'da yaşıyorum.".toByteArray(charset("windows-1254"))
        assertEquals("Güneş ışığı çok güzel, İstanbul'da yaşıyorum.", BookImporter.decodeText(old))
        assertEquals("hi", BookImporter.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'i'.code.toByte())))
    }

    @Test fun splitsTextIntoChaptersAtHeadings() {
        val body = ("A long sentence that keeps going so the paragraph is long enough to be joined. ".repeat(30) + "\n\n")
        val text = "Chapter 1\n\n$body" + "CHAPTER 2\n\n$body" + "Bölüm 3\n\n$body"
        val chunks = BookImporter.splitText(text)
        assertEquals(3, chunks.size)
        assertEquals("Chapter 1", chunks[0].title); assertEquals("CHAPTER 2", chunks[1].title); assertEquals("Bölüm 3", chunks[2].title)
        assertTrue(chunks[0].html.contains("<h2>Chapter 1</h2>"))
    }

    @Test fun longTextWithoutHeadingsIsSplitBySize() {
        val para = "Word ".repeat(400) + "\n\n"
        val chunks = BookImporter.splitText(para.repeat(30))
        assertTrue(chunks.size > 3)
        assertEquals("Part 1", chunks[0].title)
    }

    @Test fun escapesHtml() { assertEquals("a &lt;b&gt; &amp; c", BookImporter.htmlEscape("a <b> & c")) }
}
