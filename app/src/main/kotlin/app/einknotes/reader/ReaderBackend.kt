package app.einknotes.reader

import android.view.View

/** What the reader screen needs from the thing that shows the book (html pages, PDF pages, comic pages). */
interface ReaderBackend {
    val view: View
    fun next()
    fun prev()
    /** "Chapter 3 of 20  ·  page 5/32" */
    fun label(): String
    /** 0..1 through the whole book */
    fun percent(): Float
    fun chapterTitle(): String
    fun relayout()
    fun savePosition(state: ReadingState)
    fun destroy()
}
