package app.einknotes.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Books and PDFs that arrive through Ink Drop (the tablet's file-sharing app) are added to the Books tab without opening
 * Ink Notes. Only apps signed with the same key may send this (permission app.einknotes.permission.ADD_BOOKS).
 */
class BookDropReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val uri = intent?.data ?: return
        val name = intent.getStringExtra("name")
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "book-drop") {
            try {
                val r = BookImporter.importBook(app, uri, name)
                Log.i("Books", "Ink Drop: ${if (r.isNew) "added" else "already had"} ${r.book.title}")
            } catch (e: Throwable) {
                Log.w("Books", "Ink Drop: could not add $name", e)
            } finally {
                pending.finish()
            }
        }
    }
}
