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
 *   "INKN" | version int
 *   v3:  title utf | created long | modified long
 *   pageCount int
 *   per page:   paper int | width float | height float | hasPdf byte [pdfName utf | pdfPage int] | itemCount int
 *   per item (v3): type byte, then the item's data
 *      type 1 stroke: brush byte | gray int | widthScale float | nibAngle float | nibFlat float | n int | n*(x,y,pressure)
 *      type 2 shape:  kind byte | closed byte | gray int | widthScale float | n int | n floats
 *   (v1/v2 items are strokes without a type byte; v1 pages have no size or pdf fields)
 */
object NoteStore {
    private const val MAGIC = 0x494E4B4E   // "INKN"
    private const val VERSION = 3

    const val ITEM_STROKE = 1
    const val ITEM_SHAPE = 2

    class Header(val title: String, val created: Long, val modified: Long, val pageCount: Int)

    fun save(note: NoteData, file: File) {
        file.parentFile?.mkdirs()
        note.modified = System.currentTimeMillis()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(note.title); out.writeLong(note.created); out.writeLong(note.modified)
            out.writeInt(note.pages.size)
            for (page in note.pages) {
                out.writeInt(page.paper.ordinal)
                out.writeFloat(page.w); out.writeFloat(page.h)
                val pdf = page.pdfFile
                out.writeByte(if (pdf != null) 1 else 0)
                if (pdf != null) { out.writeUTF(pdf); out.writeInt(page.pdfPage) }
                out.writeInt(page.items.size)
                for (item in page.items) writeItem(out, item)
            }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw java.io.IOException("could not move ${tmp.name} into place")
    }

    private fun writeItem(out: DataOutputStream, item: Item) {
        when (item) {
            is Stroke -> {
                out.writeByte(ITEM_STROKE)
                out.writeByte(item.brush.ordinal); out.writeInt(item.gray); out.writeFloat(item.widthScale)
                out.writeFloat(item.nibAngle); out.writeFloat(item.nibFlat)
                out.writeInt(item.n)
                for (i in 0 until item.n) { out.writeFloat(item.xs[i]); out.writeFloat(item.ys[i]); out.writeFloat(item.ps[i]) }
            }
            is ShapeItem -> {
                out.writeByte(ITEM_SHAPE)
                out.writeByte(item.kind.ordinal); out.writeByte(if (item.closed) 1 else 0)
                out.writeInt(item.gray); out.writeFloat(item.widthScale)
                out.writeInt(item.pts.size)
                for (v in item.pts) out.writeFloat(v)
            }
            else -> throw IllegalStateException("unknown item type ${item.javaClass.simpleName}")
        }
    }

    /** Changes a notebook's title. */
    fun rename(file: File, title: String) {
        val note = load(file)
        note.title = title
        save(note, file)
    }

    /** Reads just enough of the file to list it in the library. Never throws; returns null for unreadable files. */
    fun readHeader(file: File): Header? {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 4096)).use { inp ->
                if (inp.readInt() != MAGIC) return null
                val version = inp.readInt()
                if (version >= 3) {
                    val title = inp.readUTF(); val created = inp.readLong(); val modified = inp.readLong()
                    Header(title, created, modified, inp.readInt())
                } else {
                    val pages = inp.readInt()
                    Header("Notebook", file.lastModified(), file.lastModified(), pages)
                }
            }
        } catch (_: Throwable) { null }
    }

    /** Returns the saved notebook, or a fresh one when there is nothing (or nothing readable) yet. */
    fun load(file: File, defaultTitle: String = "Notebook"): NoteData {
        if (!file.exists()) return NoteData(defaultTitle)
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC) { "not a notebook file" }
                val version = inp.readInt()
                require(version in 1..3) { "unknown notebook version" }
                val note = if (version >= 3) {
                    val title = inp.readUTF(); val created = inp.readLong(); val modified = inp.readLong()
                    NoteData(title, created).also { it.modified = modified }
                } else NoteData(defaultTitle, file.lastModified())
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
                    repeat(count) { readItem(inp, version)?.let { page.items.add(it) } }
                    note.pages.add(page)
                }
                if (note.pages.isEmpty()) note.pages.add(PageData())
                note
            }
        } catch (t: Throwable) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            try { file.renameTo(File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis())) } catch (_: Throwable) { }
            NoteData(defaultTitle)
        }
    }

    private fun readItem(inp: DataInputStream, version: Int): Item? {
        val type = if (version >= 3) inp.readByte().toInt() else ITEM_STROKE
        return when (type) {
            ITEM_STROKE -> {
                if (version >= 3) {
                    val brush = Brush.values().getOrElse(inp.readByte().toInt()) { Brush.PEN }
                    val gray = inp.readInt(); val widthScale = inp.readFloat()
                    val angle = inp.readFloat(); val flat = inp.readFloat()
                    val n = inp.readInt()
                    val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n)
                    for (i in 0 until n) { xs[i] = inp.readFloat(); ys[i] = inp.readFloat(); ps[i] = inp.readFloat() }
                    Stroke(brush, gray, widthScale, xs, ys, ps, angle, flat)
                } else {
                    inp.readInt()   // old tool id: every old stroke was a black pen stroke
                    val n = inp.readInt()
                    val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n)
                    for (i in 0 until n) { xs[i] = inp.readFloat(); ys[i] = inp.readFloat(); ps[i] = inp.readFloat() }
                    Stroke(Brush.PEN, 0, 1f, xs, ys, ps)
                }
            }
            ITEM_SHAPE -> {
                val kind = ShapeKind.values().getOrElse(inp.readByte().toInt()) { ShapeKind.LINE }
                val closed = inp.readByte().toInt() == 1
                val gray = inp.readInt(); val ws = inp.readFloat()
                val n = inp.readInt()
                val pts = FloatArray(n) { inp.readFloat() }
                ShapeItem(kind, pts, closed, gray, ws)
            }
            else -> throw java.io.IOException("unknown item type $type")
        }
    }
}
