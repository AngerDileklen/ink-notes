package app.einknotes.reader

import android.content.Context
import java.io.File

/** Old Kindle (MOBI / PalmDoc) books. Filled in below. */
object MobiReader {
    fun import(ctx: Context, id: String, file: File, baseName: String): Book =
        throw ImportException("Kindle files are not supported yet")
}
