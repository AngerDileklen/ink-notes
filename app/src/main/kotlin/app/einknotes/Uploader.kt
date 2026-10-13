package app.einknotes

import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Sends text or files to the Mac helper script on the local network. Nothing leaves your network. */
object Uploader {
    private fun base(url: String) = if (url.endsWith("/")) url else "$url/"

    fun postText(url: String, text: String): String {
        val c = URL(base(url)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 20000; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(text.toByteArray()) }
        return "HTTP ${c.responseCode}"
    }

    fun postFile(url: String, file: File, name: String, progress: (Long, Long) -> Unit): String {
        val c = URL(base(url) + "file?name=" + URLEncoder.encode(name, "UTF-8")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 60000; c.doOutput = true
        c.setFixedLengthStreamingMode(file.length())
        var sent = 0L
        FileInputStream(file).use { input ->
            c.outputStream.use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    progress(sent, file.length())
                }
            }
        }
        return "HTTP ${c.responseCode}"
    }

    fun done(url: String): String {
        val c = URL(base(url) + "done").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 6000; c.doOutput = true
        c.outputStream.use { it.write(0) }
        return "HTTP ${c.responseCode}"
    }
}
