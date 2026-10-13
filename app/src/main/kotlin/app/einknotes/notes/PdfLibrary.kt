package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Stores imported PDFs and draws their pages. Android's PdfRenderer can only have one page open at a time
 * and is not thread safe, so every call here is serialised. PDFs are copied into the app once and are
 * never modified: notebook pages only point at (file, page number).
 */
class PdfLibrary(context: Context) {
    val dir = File(context.filesDir, "notes/pdfs").also { it.mkdirs() }

    private var openName: String? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    class ImportException(message: String) : Exception(message)

    /** Copies the PDF behind [uri] into the library and returns its stored file name. */
    fun importFrom(context: Context, uri: Uri): String {
        val tmp = File(dir, "import-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Cannot open that file")
            input.use { src ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                    }
                }
            }
            val name = digest.digest().joinToString("") { "%02x".format(it) }.take(24) + ".pdf"
            val target = File(dir, name)
            if (target.exists()) { tmp.delete() } else if (!tmp.renameTo(target)) throw ImportException("Cannot store the PDF")
            // Make sure it really is a readable PDF before anyone points a page at it.
            pageCount(name)
            return name
        } catch (e: ImportException) {
            tmp.delete(); throw e
        } catch (e: SecurityException) {
            tmp.delete(); throw ImportException("This PDF is password protected")
        } catch (e: Throwable) {
            tmp.delete(); throw ImportException("This does not look like a readable PDF")
        }
    }

    @Synchronized
    fun pageCount(name: String): Int = ensure(name).pageCount

    /** Size of a page in PDF points. */
    @Synchronized
    fun pageSize(name: String, index: Int): Pair<Int, Int> {
        val page = ensure(name).openPage(index)
        try { return page.width to page.height } finally { page.close() }
    }

    /**
     * Draws page [index] into [target] (view sized). [pageUnitsW] is the width the page occupies in page
     * units, [scale] converts page units to view pixels, and (tx, ty) is where the page's top-left lands.
     */
    @Synchronized
    fun render(name: String, index: Int, target: Bitmap, pageUnitsW: Float, scale: Float, tx: Float, ty: Float): Boolean {
        return try {
            val page = ensure(name).openPage(index)
            try {
                val s = pageUnitsW * scale / page.width
                val m = Matrix().apply { setScale(s, s); postTranslate(tx, ty) }
                target.eraseColor(Color.WHITE)   // PdfRenderer expects an opaque white background
                page.render(target, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                true
            } finally { page.close() }
        } catch (t: Throwable) { false }
    }

    private fun ensure(name: String): PdfRenderer {
        if (openName == name) renderer?.let { return it }
        close()
        val pfd = ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        descriptor = pfd; renderer = r; openName = name
        return r
    }

    @Synchronized
    fun close() {
        try { renderer?.close() } catch (_: Throwable) { }
        try { descriptor?.close() } catch (_: Throwable) { }
        renderer = null; descriptor = null; openName = null
    }
}
