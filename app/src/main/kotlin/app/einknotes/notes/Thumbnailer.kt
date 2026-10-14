package app.einknotes.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

/** Small pictures of a page for the library and the page manager. */
object Thumbnailer {
    private val renderer = PageRenderer()

    fun render(page: PageData, pdfs: PdfLibrary, widthPx: Int): Bitmap {
        val scale = widthPx / page.w
        val h = (page.h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val pdf = page.pdfFile
        if (pdf != null) {
            pdfs.render(pdf, page.pdfPage, bmp, page.w, scale, 0f, 0f)
            renderer.drawBorder(c, page, scale, 0f, 0f)
        } else renderer.drawPaper(c, page, scale, 0f, 0f)
        renderer.drawItems(c, page, scale, 0f, 0f)
        return bmp
    }

    fun save(page: PageData, pdfs: PdfLibrary, widthPx: Int, file: File) {
        val bmp = render(page, pdfs, widthPx)
        try { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } } finally { bmp.recycle() }
    }
}
