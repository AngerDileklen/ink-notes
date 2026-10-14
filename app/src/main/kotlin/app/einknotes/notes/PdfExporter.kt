package app.einknotes.notes

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Writes a notebook out as an ordinary PDF: the original PDF page (or the paper) underneath, and everything
 * written or drawn on top as real vector lines. Saved in Downloads/Ink Notes.
 *
 * Android can only write new PDFs, not copy pages of old ones, so the PDF page underneath is stored as a
 * picture (about 150 dpi). The ink on top stays sharp at any zoom.
 */
object PdfExporter {

    class Result(val uri: Uri, val name: String, val pages: Int, val bytes: Long)

    private const val BACKGROUND_DPI = 150f

    fun export(ctx: Context, note: NoteData, pdfs: PdfLibrary, progress: (Int, Int) -> Unit): Result {
        val doc = PdfDocument()
        val renderer = PageRenderer(forExport = true)
        val smooth = Paint().apply { isFilterBitmap = true }
        try {
            for ((i, page) in note.pages.withIndex()) {
                val pdf = page.pdfFile
                // page size in PDF points (1/72 inch)
                val (wPt, hPt) = if (pdf != null) pdfs.pageSize(pdf, page.pdfPage) else 595 to (595 * page.h / page.w).toInt()
                val info = PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create()
                val pg = doc.startPage(info)
                val c = pg.canvas
                c.drawColor(Color.WHITE)
                val scale = wPt / page.w                      // page units -> points
                if (pdf != null) {
                    val px = (wPt * BACKGROUND_DPI / 72f).toInt().coerceAtLeast(1)
                    val py = (hPt * BACKGROUND_DPI / 72f).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888)
                    if (pdfs.render(pdf, page.pdfPage, bmp, page.w, px / page.w, 0f, 0f)) c.drawBitmap(bmp, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), smooth)
                    bmp.recycle()
                } else renderer.drawPaper(c, page, scale, 0f, 0f)
                renderer.drawItems(c, page, scale, 0f, 0f)
                doc.finishPage(pg)
                progress(i + 1, note.pages.size)
            }
            return write(ctx, doc, note.title, note.pages.size)
        } finally { doc.close() }
    }

    private fun safeName(title: String): String {
        val t = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Notebook" }
        return t.take(80)
    }

    private fun write(ctx: Context, doc: PdfDocument, title: String, pages: Int): Result {
        val name = safeName(title) + ".pdf"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ink Notes")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the file in Downloads")
            ctx.contentResolver.openOutputStream(uri)!!.use { doc.writeTo(it) }
            val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            return Result(uri, name, pages, size)
        }
        val dir = File(ctx.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { doc.writeTo(it) }
        return Result(Uri.fromFile(f), name, pages, f.length())
    }
}
