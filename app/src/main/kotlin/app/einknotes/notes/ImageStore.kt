package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Pictures placed on pages. Files are copied into the app once (named by their content) and decoded at the size needed. */
object ImageStore {
    private var dir: File? = null
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    class Imported(val name: String, val width: Int, val height: Int)

    fun init(ctx: Context) {
        if (dir == null) dir = File(ctx.filesDir, "notes/images").also { it.mkdirs() }
    }

    fun importFrom(ctx: Context, uri: Uri): Imported {
        init(ctx)
        val tmp = File(dir, "import-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { src ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = src.read(buf); if (n < 0) break; out.write(buf, 0, n); digest.update(buf, 0, n) }
                }
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) throw java.io.IOException("This is not a picture I can read")
            val name = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
            val target = File(dir, name)
            if (target.exists()) tmp.delete() else if (!tmp.renameTo(target)) throw java.io.IOException("Cannot store the picture")
            return Imported(name, opts.outWidth, opts.outHeight)
        } catch (t: Throwable) { tmp.delete(); throw t }
    }

    /** The picture decoded for display at about [wantWidth] pixels across, or null if it cannot be read. */
    fun bitmap(name: String, wantWidth: Int): Bitmap? {
        val d = dir ?: return null
        val f = File(d, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= wantWidth.coerceAtLeast(64)) sample *= 2
        val key = "$name@$sample"
        cache.get(key)?.let { return it }
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun collectGarbage(referenced: Set<String>) {
        for (f in dir?.listFiles() ?: return) if (f.name !in referenced) f.delete()
        cache.evictAll()
    }
}
