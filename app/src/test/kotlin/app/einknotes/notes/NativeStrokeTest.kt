package app.einknotes.notes

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File

class NativeStrokeTest {
    private fun stroke() = Stroke(Brush.NATIVE_WIDTH, 0, 1f,
        floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 0f), FloatArray(0),
        renderedWidths = floatArrayOf(2f, 4f, 6f)).also { it.recId = 3; it.recMs = 900 }
    private fun file() = File(kotlin.io.path.createTempDirectory("native-note").toFile(), "note.inkn")
    private fun version(f: File) = DataInputStream(f.inputStream()).use { it.readInt(); it.readInt() }
    @Test fun legacySaveRemainsV7AndNativeUpgradePreservesOriginal() {
        val note = NoteData("Compatibility"); val f = file()
        NoteStore.save(note, f); val original = f.readBytes()
        assertEquals(7, version(f))
        note.pages[0].items += stroke(); NoteStore.save(note, f)
        assertEquals(8, version(f))
        assertArrayEquals(original, File(f.parentFile, f.name + ".pre-v8.bak").readBytes())
        val loaded = NoteStore.load(f).pages[0].items.single() as Stroke
        assertEquals(Brush.NATIVE_WIDTH, loaded.brush); assertEquals(0, loaded.ps.size)
        assertArrayEquals(floatArrayOf(2f, 4f, 6f), loaded.renderedWidths!!, 0f)
        assertEquals(3, loaded.recId); assertEquals(900, loaded.recMs)
        NoteStore.save(note, f)
        assertArrayEquals(original, File(f.parentFile, f.name + ".pre-v8.bak").readBytes())
    }
    @Test fun copyingAndPartialErasingPreserveWidthsAndRecordingAssociation() {
        val stroke = stroke(); val copy = stroke.copy() as Stroke
        copy.renderedWidths!![0] = 9f
        assertEquals(2f, stroke.renderedWidths!![0], 0f)
        val parts = stroke.eraseCircle(10f, 0f, 2f)!!
        assertEquals(2, parts.size)
        assertEquals(3.6f, parts[0].renderedWidths!!.last(), .001f)
        assertEquals(4.4f, parts[1].renderedWidths!!.first(), .001f)
        assertTrue(parts.all { it.ps.isEmpty() && it.brush == Brush.NATIVE_WIDTH && it.recId == 3 && it.recMs == 900 })
    }
    @Test fun widthBoundsAndMalformedWidths() {
        val stroke = stroke()
        assertEquals(24f, stroke.maxX, .001f)
        assertThrows(IllegalArgumentException::class.java) {
            Stroke(Brush.NATIVE_WIDTH, 0, 1f, floatArrayOf(1f), floatArrayOf(1f), FloatArray(0), renderedWidths = floatArrayOf(Float.NaN))
        }
        val note = NoteData("bad"); val f = file(); NoteStore.save(note, f); val original = f.readBytes()
        stroke.renderedWidths!![0] = -1f; note.pages[0].items += stroke
        assertThrows(IllegalArgumentException::class.java) { NoteStore.save(note, f) }
        assertArrayEquals(original, f.readBytes())
    }
}
