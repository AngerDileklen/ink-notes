package app.einknotes.notes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A notebook packed into one ".inkz" file (a zip): the notebook itself plus the PDFs, pictures and audio it uses.
 * That is the backup format and also how a notebook moves to another tablet.
 */
object NotebookBundle {
    class Result(val uri: Uri, val name: String, val bytes: Long)

    private val SAFE = Regex("^[A-Za-z0-9._-]+$")

    private fun notesDir(ctx: Context) = File(ctx.filesDir, "notes")

    fun export(ctx: Context, noteFile: File): Result {
        val note = NoteStore.load(noteFile)
        val notes = notesDir(ctx)
        val tmp = File(ctx.cacheDir, "bundle-${System.nanoTime()}.inkz")
        ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
            fun addFile(entry: String, f: File) {
                if (!f.exists()) return
                zip.putNextEntry(ZipEntry(entry)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            addFile("notebook.inkn", noteFile)
            val pdfs = HashSet<String>(); val images = HashSet<String>()
            for (p in note.pages) {
                p.pdfFile?.let { pdfs.add(it) }
                for (it in p.items) if (it is ImageItem) images.add(it.file)
            }
            for (n in pdfs) addFile("pdf/$n", File(notes, "pdfs/$n"))
            for (n in images) addFile("img/$n", File(notes, "images/$n"))
            for (r in note.recordings) addFile("audio/${r.file}", File(notes, "audio/${r.file}"))
            addFile("study.json", File(noteFile.parentFile, noteFile.name.removeSuffix(".inkn") + ".study.json"))
        }
        try { return publish(ctx, note.title, tmp) } finally { tmp.delete() }
    }

    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Notebook" }.take(80)

    private fun publish(ctx: Context, title: String, src: File): Result {
        val name = safeName(title) + ".inkz"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ink Notes")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the file in Downloads")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            return Result(uri, name, src.length())
        }
        val dir = File(ctx.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val f = File(dir, name)
        src.copyTo(f, overwrite = true)
        return Result(Uri.fromFile(f), name, f.length())
    }

    /** Unpacks a bundle into the app and returns the new notebook's id. */
    fun import(ctx: Context, uri: Uri, newId: String): String {
        val notes = notesDir(ctx)
        val stage = File(ctx.cacheDir, "import-${System.nanoTime()}").also { it.mkdirs() }
        var found = false
        try {
            ZipInputStream(ctx.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val slash = e.name.indexOf('/')
                    val kind = if (slash < 0) e.name else e.name.substring(0, slash)
                    val leaf = e.name.substringAfterLast('/')
                    if (!SAFE.matches(leaf)) continue                      // never trust a path inside an archive
                    val target = when (kind) {
                        "notebook.inkn" -> File(stage, "notebook.inkn").also { found = true }
                        "pdf" -> File(notes, "pdfs/$leaf")
                        "img" -> File(notes, "images/$leaf")
                        "audio" -> File(notes, "audio/$leaf")
                        "study.json" -> File(stage, "study.json")
                        else -> continue
                    }
                    if (kind != "notebook.inkn" && target.exists()) continue   // content-named files are identical
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                }
            }
            if (!found) throw java.io.IOException("That file is not an Ink Notes backup")
            val inkn = File(stage, "notebook.inkn")
            if (NoteStore.readHeader(inkn) == null) throw java.io.IOException("The notebook inside is damaged")
            inkn.copyTo(File(notes, "$newId.inkn"), overwrite = false)
            File(stage, "study.json").takeIf { it.exists() }?.copyTo(File(notes, "$newId.study.json"), overwrite = false)
            return newId
        } finally { stage.deleteRecursively() }
    }
}
