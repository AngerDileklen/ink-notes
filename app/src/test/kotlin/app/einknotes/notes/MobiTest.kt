package app.einknotes.notes

import app.einknotes.reader.MobiReader
import org.junit.Assert.assertEquals
import org.junit.Test

class MobiTest {
    @Test fun palmDocLiteralsAndBackReferences() {
        // "hello " as literals, then a copy of 5 bytes from 6 back (distance 6, length 5): 0x8000 | (6 << 3) | (5 - 3)
        val pair = 0x8000 or (6 shl 3) or 2
        val data = "hello ".toByteArray() + byteArrayOf((pair shr 8).toByte(), (pair and 0xFF).toByte())
        assertEquals("hello hello", String(MobiReader.palmDecompress(data)))
    }

    @Test fun palmDocSpaceAndLiteralRuns() {
        // 0xC0 | 'a' means " " + ('a' ^ 0x80...): byte 0xE1 -> space + 'a'; 0x03 copies the next three bytes as they are
        assertEquals(" a", String(MobiReader.palmDecompress(byteArrayOf(0xE1.toByte()))))
        assertEquals("xyz", String(MobiReader.palmDecompress(byteArrayOf(3, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()))))
    }

    @Test fun trailingEntriesAreMeasured() {
        // one trailing entry (flag bit 1): size byte 0x82 = 2 bytes long including itself, with the end marker bit set
        val rec = "text".toByteArray() + byteArrayOf(0x41, 0x82.toByte())
        assertEquals(2, MobiReader.trailingSize(rec, 2))
        // multibyte-overlap bit (flag bit 0): the last byte & 3, plus one
        assertEquals(2, MobiReader.trailingSize("abc".toByteArray() + byteArrayOf(0x01), 1))
        assertEquals(0, MobiReader.trailingSize("abc".toByteArray(), 0))
    }
}
