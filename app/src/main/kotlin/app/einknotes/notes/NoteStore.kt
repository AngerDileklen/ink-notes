package app.einknotes.notes

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Compact binary file for one notebook. Written to a temp file and renamed, so a crash or a power loss
 * in the middle of saving can never destroy the previous good copy.
 *
 *   "INKN" | version int | pageCount int
 *   per page:   paper int | [v2: width float | height float | hasPdf byte | (pdfName utf | pdfPage int)] | strokeCount int
 *   per stroke: tool int | pointCount int | (x, y, pressure) floats...
 */
object NoteStore {
    private const val MAGIC = 0x494E4B4E   // "INKN"
    private const val VERSION = 2

    fun save(note: NoteData, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(note.pages.size)
            for (page in note.pages) {
                out.writeInt(page.paper.ordinal)
                out.writeFloat(page.w); out.writeFloat(page.h)
                val pdf = page.pdfFile
                out.writeByte(if (pdf != null) 1 else 0)
                if (pdf != null) { out.writeUTF(pdf); out.writeInt(page.pdfPage) }
                out.writeInt(page.strokes.size)
                for (s in page.strokes) {
                    out.writeInt(s.tool.ordinal)
                    out.writeInt(s.n)
                    for (i in 0 until s.n) { out.writeFloat(s.xs[i]); out.writeFloat(s.ys[i]); out.writeFloat(s.ps[i]) }
                }
            }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw java.io.IOException("could not move ${tmp.name} into place")
    }

    /** Returns the saved notebook, or a fresh one when there is nothing (or nothing readable) yet. */
    fun load(file: File): NoteData {
        if (!file.exists()) return NoteData()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC) { "not a notebook file" }
                val version = inp.readInt()
                require(version == 1 || version == 2) { "unknown notebook version" }
                val note = NoteData()
                note.pages.clear()
                val pages = inp.readInt()
                repeat(pages) {
                    val paper = Paper.values().getOrElse(inp.readInt()) { Paper.RULED }
                    val page = PageData(paper)
                    if (version >= 2) {
                        page.w = inp.readFloat(); page.h = inp.readFloat()
                        if (inp.readByte().toInt() == 1) { page.pdfFile = inp.readUTF(); page.pdfPage = inp.readInt() }
                    }
                    val count = inp.readInt()
                    repeat(count) {
                        val tool = Tool.values().getOrElse(inp.readInt()) { Tool.PEN }
                        val n = inp.readInt()
                        val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n)
                        for (i in 0 until n) { xs[i] = inp.readFloat(); ys[i] = inp.readFloat(); ps[i] = inp.readFloat() }
                        page.strokes.add(Stroke(tool, xs, ys, ps))
                    }
                    note.pages.add(page)
                }
                if (note.pages.isEmpty()) note.pages.add(PageData())
                note
            }
        } catch (t: Throwable) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            try { file.renameTo(File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis())) } catch (_: Throwable) { }
            NoteData()
        }
    }
}
