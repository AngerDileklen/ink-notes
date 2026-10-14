package app.einknotes.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.concurrent.Executors

/** A comic archive (CBZ): one picture per screen, fitted to the screen, turned with the keys or a tap at the edges. */
@SuppressLint("ViewConstructor")
class ComicBookView(context: Context, private val book: Book, private val dir: File, private val onPosition: () -> Unit, private val onTapMiddle: () -> Unit) : View(context), ReaderBackend {
    var page = 0; private set
    private var bitmap: Bitmap? = null
    private var token = 0
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    override val view: View get() = this

    fun open(p: Int) { page = p.coerceIn(0, maxOf(0, book.spine.size - 1)); load() }

    private fun load() {
        if (width <= 0) return
        val my = ++token; val idx = page; val w = width; val h = height
        exec.execute {
            val f = File(dir, book.spine[idx])
            val bmp = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (o.outWidth / (s * 2) >= w * 1.5 && o.outHeight / (s * 2) >= h * 1.5) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) { null }
            ui.post { if (my == token) { val old = bitmap; bitmap = bmp; if (old !== bmp) old?.recycle(); invalidate() } else bmp?.recycle() }
        }
        onPosition()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { load() }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        val b = bitmap ?: return
        val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val dw = b.width * s; val dh = b.height * s
        c.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), null)
    }

    override fun next() { if (page + 1 < book.spine.size) { page++; load() } }
    override fun prev() { if (page > 0) { page--; load() } }
    override fun label() = "Page ${page + 1} of ${book.spine.size}"
    override fun percent() = if (book.spine.size > 1) page / (book.spine.size - 1f) else 0f
    override fun chapterTitle() = ""
    override fun relayout() { load() }
    override fun savePosition(state: ReadingState) { state.page = page }
    override fun destroy() { token++; exec.shutdownNow(); bitmap?.recycle(); bitmap = null }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) when {
            e.x < width * 0.35f -> prev()
            e.x > width * 0.65f -> next()
            else -> onTapMiddle()
        }
        return true
    }
}
