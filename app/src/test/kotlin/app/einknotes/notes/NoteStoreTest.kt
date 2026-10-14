package app.einknotes.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class NoteStoreTest {

    private fun tmpDir(): File = createTempFile("notes", "").let { it.delete(); it.mkdirs(); it }

    @Test fun roundTripKeepsPagesItemsAndRecordings() {
        val note = NoteData("Round trip")
        val p = note.pages[0]
        p.bookmarked = true
        p.items.add(Stroke(Brush.PEN, 30, 1.5f, floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f), floatArrayOf(.1f, .5f, .9f)).also { it.recId = 2; it.recMs = 1234 })
        p.items.add(ShapeItem(ShapeKind.POLYGON, floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), true, 0, 2f))
        p.items.add(TapeItem(1f, 2f, 30f, 40f, covered = false))
        p.items.add(ImageItem("abc", 0f, 0f, 100f, 50f, 800, 400))
        note.pages.add(PageData(Paper.GRID).apply { pdfFile = "x.pdf"; pdfPage = 3; w = 1000f; h = 1400f; spacing = 33.4f })
        note.recordings.add(Recording(2, "rec-a.m4a", 99L, 5000))
        val f = File(tmpDir(), "n.inkn")
        NoteStore.save(note, f)
        val back = NoteStore.load(f)
        assertEquals("Round trip", back.title)
        assertEquals(2, back.pages.size)
        assertTrue(back.pages[0].bookmarked)
        assertEquals(4, back.pages[0].items.size)
        val s = back.pages[0].items[0] as Stroke
        assertEquals(3, s.n); assertEquals(2, s.recId); assertEquals(1234, s.recMs); assertEquals(30, s.gray)
        assertEquals(false, (back.pages[0].items[2] as TapeItem).covered)
        assertEquals("x.pdf", back.pages[1].pdfFile); assertEquals(3, back.pages[1].pdfPage)
        assertEquals(5000, back.recordings[0].durationMs)
        assertEquals(33.4f, back.pages[1].spacing, 0.001f)
        assertEquals(64f, back.pages[0].spacing, 0.001f)
        assertTrue(!File(f.parentFile, "n.inkn.tmp").exists())
    }

    @Test fun damagedFileThrowsAndIsLeftAlone() {
        val f = File(tmpDir(), "bad.inkn")
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try { NoteStore.load(f); fail("should throw") } catch (_: NoteStore.Unreadable) { }
        assertTrue(f.exists())
        assertEquals(5L, f.length())
        assertTrue(f.parentFile!!.listFiles()!!.size == 1)      // nothing renamed or created
    }

    @Test fun missingFileGivesFreshNotebook() {
        val note = NoteStore.load(File(tmpDir(), "none.inkn"), "Fresh")
        assertEquals("Fresh", note.title)
        assertEquals(1, note.pages.size)
    }

    @Test fun longTextBeyond64KbSurvives() {
        val text = "ğ".repeat(50_000) + "end"          // 100 KB of UTF-8, too big for writeUTF
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { NoteStore.writeLongUtf(it, text) }
        val back = NoteStore.readLongUtf(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(text, back)
    }

    @Test fun untouchedSaveKeepsModifiedTime() {
        val note = NoteData("T"); note.modified = 42L
        val f = File(tmpDir(), "t.inkn")
        NoteStore.save(note, f, touch = false)
        assertEquals(42L, NoteStore.readHeader(f)!!.modified)
    }

    @Test fun newPagesAreA4AndCopyPaperFromTheCurrentPage() {
        val a = PageData(Paper.RULED)
        assertEquals(PageSize.A4, PageSize.of(a.w, a.h))
        val tablet = PageData(Paper.GRID).apply { h = w * 4f / 3f; spacing = 40f }
        val b = blankPageLike(tablet)
        assertEquals(Paper.GRID, b.paper); assertEquals(40f, b.spacing, 0.001f)
        assertEquals(PageSize.TABLET, PageSize.of(b.w, b.h))
        val pdf = PageData().apply { pdfFile = "x.pdf" }
        assertEquals(Paper.BLANK, blankPageLike(pdf).paper)
    }
}
