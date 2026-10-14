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
 *   per page:   paper int | width float | height float | hasPdf byte [pdfName utf | pdfPage int] | v4: flags byte (bit0 bookmarked) | itemCount int
 *   per item (v3): type byte, then the item's data
 *      type 1 stroke: brush byte | gray int | widthScale float | nibAngle float | nibFlat float | n int | n*(x,y,pressure)
 *      type 2 shape:  kind byte | closed byte | gray int | widthScale float | n int | n floats
 *      type 3 text:   text utf | x,y,boxW,size floats | gray int | bold byte
 *      type 4 image:  file utf | l,t,r,b floats | srcW int | srcH int
 *      type 5 tape:   l,t,r,b floats | covered byte
 *   v7: per page: spacing float after the flags byte
 *   v6: text-box text is an int byte length + UTF-8 bytes (no 64 KB limit)
 *   v5: every item is followed by recId int | recMs int; after the last page: recordingCount int, per recording id int | file utf | startedAt long | durationMs int
 *   (v1/v2 items are strokes without a type byte; v1 pages have no size or pdf fields)
 */
object NoteStore {
    private const val MAGIC = 0x494E4B4E   // "INKN"
    private const val VERSION = 7

    const val ITEM_STROKE = 1
    const val ITEM_SHAPE = 2
    const val ITEM_TEXT = 3
    const val ITEM_IMAGE = 4
    const val ITEM_TAPE = 5

    class Header(val title: String, val created: Long, val modified: Long, val pageCount: Int)

    /** The file exists but cannot be read. Callers must never save over it. */
    class Unreadable(val file: File, cause: Throwable) : java.io.IOException("Cannot read ${file.name}: ${cause.message}", cause)

    /**
     * Writes the notebook to a temp file, forces it to the storage chip, then renames it over the old file.
     * rename() replaces the old file in one step, so a crash or power cut leaves either the old or the new file.
     * [touch] = false keeps the "edited" time (used when nothing was changed).
     */
    fun save(note: NoteData, file: File, touch: Boolean = true) {
        file.parentFile?.mkdirs()
        if (touch) note.modified = System.currentTimeMillis()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        DataOutputStream(BufferedOutputStream(fos, 1 shl 16)).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeUTF(note.title); out.writeLong(note.created); out.writeLong(note.modified)
            out.writeInt(note.pages.size)
            for (page in note.pages) {
                out.writeInt(page.paper.ordinal)
                out.writeFloat(page.w); out.writeFloat(page.h)
                val pdf = page.pdfFile
                out.writeByte(if (pdf != null) 1 else 0)
                if (pdf != null) { out.writeUTF(pdf); out.writeInt(page.pdfPage) }
                out.writeByte(if (page.bookmarked) 1 else 0)
                out.writeFloat(page.spacing)
                out.writeInt(page.items.size)
                for (item in page.items) { writeItem(out, item); out.writeInt(item.recId); out.writeInt(item.recMs) }
            }
            out.writeInt(note.recordings.size)
            for (r in note.recordings) { out.writeInt(r.id); out.writeUTF(r.file); out.writeLong(r.startedAt); out.writeInt(r.durationMs) }
            out.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("could not move ${tmp.name} into place")
    }

    fun writeLongUtf(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size); out.write(b)
    }

    fun readLongUtf(inp: DataInputStream): String {
        val n = inp.readInt()
        require(n in 0..(64 shl 20)) { "bad text length $n" }
        val b = ByteArray(n); inp.readFully(b)
        return String(b, Charsets.UTF_8)
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
            is TextItem -> {
                out.writeByte(ITEM_TEXT)
                writeLongUtf(out, item.text)
                out.writeFloat(item.x); out.writeFloat(item.y); out.writeFloat(item.boxW); out.writeFloat(item.size)
                out.writeInt(item.gray); out.writeByte(if (item.bold) 1 else 0)
            }
            is TapeItem -> {
                out.writeByte(ITEM_TAPE)
                out.writeFloat(item.l); out.writeFloat(item.t); out.writeFloat(item.r); out.writeFloat(item.b)
                out.writeByte(if (item.covered) 1 else 0)
            }
            is ImageItem -> {
                out.writeByte(ITEM_IMAGE)
                out.writeUTF(item.file)
                out.writeFloat(item.l); out.writeFloat(item.t); out.writeFloat(item.r); out.writeFloat(item.b)
                out.writeInt(item.srcW); out.writeInt(item.srcH)
            }
            else -> throw IllegalStateException("unknown item type ${item.javaClass.simpleName}")
        }
    }

    /** Changes a notebook's title. Throws [Unreadable] rather than touching a damaged file. */
    fun rename(file: File, title: String) {
        val note = load(file)
        note.title = title
        save(note, file, touch = false)
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

    /**
     * Returns the saved notebook, or a fresh one when the file does not exist yet.
     * Throws [Unreadable] when the file exists but is damaged; the file is left exactly as it is.
     */
    fun load(file: File, defaultTitle: String = "Notebook"): NoteData {
        if (!file.exists()) return NoteData(defaultTitle)
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC) { "not a notebook file" }
                val version = inp.readInt()
                require(version in 1..7) { "unknown notebook version" }
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
                    if (version >= 4) page.bookmarked = (inp.readByte().toInt() and 1) != 0
                    if (version >= 7) page.spacing = inp.readFloat().coerceIn(20f, 400f)
                    val count = inp.readInt()
                    repeat(count) {
                        val item = readItem(inp, version)
                        if (version >= 5) { val id = inp.readInt(); val ms = inp.readInt(); item?.recId = id; item?.recMs = ms }
                        item?.let { page.items.add(it) }
                    }
                    note.pages.add(page)
                }
                if (version >= 5) repeat(inp.readInt()) { note.recordings.add(Recording(inp.readInt(), inp.readUTF(), inp.readLong(), inp.readInt())) }
                if (note.pages.isEmpty()) note.pages.add(PageData())
                note
            }
        } catch (t: Throwable) {
            throw Unreadable(file, t)
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
            ITEM_TEXT -> {
                val text = if (version >= 6) readLongUtf(inp) else inp.readUTF()
                val x = inp.readFloat(); val y = inp.readFloat(); val w = inp.readFloat(); val size = inp.readFloat()
                val gray = inp.readInt(); val bold = inp.readByte().toInt() == 1
                TextItem(text, x, y, w, size, gray, bold)
            }
            ITEM_TAPE -> {
                val l = inp.readFloat(); val t = inp.readFloat(); val r = inp.readFloat(); val b = inp.readFloat()
                TapeItem(l, t, r, b, inp.readByte().toInt() == 1)
            }
            ITEM_IMAGE -> {
                val file = inp.readUTF()
                val l = inp.readFloat(); val t = inp.readFloat(); val r = inp.readFloat(); val b = inp.readFloat()
                ImageItem(file, l, t, r, b, inp.readInt(), inp.readInt())
            }
            else -> throw java.io.IOException("unknown item type $type")
        }
    }
}
