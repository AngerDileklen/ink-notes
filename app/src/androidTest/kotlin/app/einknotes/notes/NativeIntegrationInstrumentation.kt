package app.einknotes.notes

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.hanvon.ink.HanvonInk
import java.io.File

/** Disposable-emulator regression test. Refuses native-capable hardware. */
class NativeIntegrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            check(!HanvonInk.probe(targetContext).available) { "Refusing synthetic test on native-capable device" }
            val file = File(targetContext.filesDir, "notes/native_sdk_fixture.inkn")
            val note = NoteData("Native SDK fixture")
            note.pages[0].items += Stroke(Brush.NATIVE_WIDTH, 0, 1f, floatArrayOf(10f, 20f, 30f),
                floatArrayOf(20f, 25f, 30f), FloatArray(0), renderedWidths = floatArrayOf(2f, 5f, 3f))
            NoteStore.save(note, file)
            fun launch() = startActivitySync(Intent(targetContext, NotesActivity::class.java)
                .putExtra("notebook", "native_sdk_fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as NotesActivity
            val activity = launch(); waitForIdleSync()
            val field = NotesActivity::class.java.getDeclaredField("ink").apply { isAccessible = true }
            val ink = field.get(activity) as InkView
            val frame = java.util.concurrent.CountDownLatch(1)
            runOnMainSync { ink.afterRetainedRedraw { frame.countDown() } }
            check(frame.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Retained redraw frame callback missing" }
            runOnMainSync {
                check(!ink.nativeDrawing)
                check((ink.note.pages[0].items.single() as Stroke).renderedWidths!!.contentEquals(floatArrayOf(2f, 5f, 3f)))
                ink.settings.shapeSnap = false; ink.settings.zoomTaps = 0
                val start = SystemClock.uptimeMillis()
                fun event(action: Int, x: Float, y: Float, buttons: Int = 0, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, source: Int = InputDevice.SOURCE_STYLUS): MotionEvent {
                    val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
                    val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .5f; size = .01f }
                    return MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                        0, buttons, 1f, 1f, 1, 0, source, 0)
                }
                event(MotionEvent.ACTION_DOWN, 20f, 20f, 32).let { check(ink.inputTool(it) == Tool.ERASER); it.recycle() }
                event(MotionEvent.ACTION_DOWN, 20f, 20f, 64).let { check(ink.inputTool(it) == Tool.SELECT); it.recycle() }
                event(MotionEvent.ACTION_DOWN, 20f, 20f, tool = MotionEvent.TOOL_TYPE_FINGER, source = InputDevice.SOURCE_TOUCHSCREEN).let {
                    check(ink.inputTool(it) == null); ink.fingerPan = true; check(ink.inputTool(it) == Tool.HAND); ink.fingerPan = false; it.recycle()
                }
                event(MotionEvent.ACTION_DOWN, 20f, 20f, tool = MotionEvent.TOOL_TYPE_FINGER, source = 0x5002).let {
                    check(ink.inputTool(it) == Tool.HAND); it.recycle()
                }
                for ((action, x) in listOf(MotionEvent.ACTION_DOWN to 100f, MotionEvent.ACTION_MOVE to 140f, MotionEvent.ACTION_UP to 180f)) {
                    event(action, x, 200f).let { ink.onTouchEvent(it); it.recycle() }
                }
                check(ink.note.pages[0].items.size == 2)
                ink.undo(); check(ink.note.pages[0].items.size == 1)
                ink.redo(); check(ink.note.pages[0].items.size == 2)
                // Exercise complete editor gestures, not just signal-to-tool lookup.
                fun gesture(points: List<Pair<Float, Float>>, buttons: Int = 0,
                    tool: Int = MotionEvent.TOOL_TYPE_STYLUS, source: Int = InputDevice.SOURCE_STYLUS) {
                    points.forEachIndexed { i, (x, y) ->
                        val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; points.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                        event(action, x, y, buttons, tool, source).let { ink.onTouchEvent(it); it.recycle() }
                    }
                }
                fun number(name: String): Float = InkView::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(ink)
                val originalItems = ink.page.items.toList()
                val editable = Stroke(Brush.NATIVE_WIDTH, 0, 1f, floatArrayOf(250f, 350f, 450f),
                    floatArrayOf(350f, 350f, 350f), FloatArray(0), renderedWidths = floatArrayOf(2f, 5f, 3f))
                ink.page.items.add(editable); ink.repaintAll()
                fun point(x: Float, y: Float) = (number("tx") + x * number("scale")) to (number("ty") + y * number("scale"))
                val center = point(350f, 350f)
                gesture(listOf(center, center), buttons = 32)
                check(editable !in ink.page.items) { "Held bottom must erase a retained native stroke" }
                ink.undo(); check(editable in ink.page.items)
                ink.redo(); check(editable !in ink.page.items)
                ink.undo()
                gesture(listOf(center, center), tool = MotionEvent.TOOL_TYPE_ERASER)
                check(editable !in ink.page.items) { "Rear eraser must remove retained native ink" }; ink.undo()
                gesture(listOf(point(200f, 300f), point(500f, 300f), point(500f, 400f), point(200f, 400f), point(200f, 300f)), buttons = 64)
                check(ink.hasSelection) { "Held middle must complete lasso selection" }
                val beforeMove = editable.xs[0]
                val moved = center.first + 40f to center.second + 25f
                gesture(listOf(center, moved, moved), buttons = 64)
                check(editable.xs[0] > beforeMove) { "Held middle must move selected native ink" }
                ink.undo(); check(kotlin.math.abs(editable.xs[0] - beforeMove) < .01f)
                ink.clearSelection()
                ink.zoomAt(ink.width / 2f, ink.height / 2f, 2f)
                val beforePan = number("tx")
                gesture(listOf(200f to 300f, 240f to 330f, 240f to 330f), tool = MotionEvent.TOOL_TYPE_FINGER, source = 0x5002)
                check(number("tx") > beforePan) { "Pen-as-finger must pan a zoomed page" }
                val afterPan = number("tx")
                gesture(listOf(200f to 300f, 240f to 330f, 240f to 330f), tool = MotionEvent.TOOL_TYPE_FINGER, source = InputDevice.SOURCE_TOUCHSCREEN)
                check(number("tx") == afterPan) { "Ignored touch must not pan" }
                ink.zoomOut(); ink.page.items.clear(); ink.page.items.addAll(originalItems); ink.repaintAll()
                val native = ink.note.pages[0].items.first() as Stroke
                val transform = android.graphics.Matrix().apply { setScale(2f, 2f) }
                native.transform(transform); check(native.widthScale == 2f)
                // Exercise the shared width renderer on both bitmap and vector PDF canvases.
                val bitmap = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
                PageRenderer().drawItem(android.graphics.Canvas(bitmap), native, 1f, 0f, 0f)
                check(bitmap.getPixel(20, 40) != 0); bitmap.recycle()
                val pdf = android.graphics.pdf.PdfDocument()
                val pg = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 300, 1).create())
                PageRenderer(forExport = true).drawItem(pg.canvas, native, 1f, 0f, 0f); pdf.finishPage(pg)
                val bytes = java.io.ByteArrayOutputStream(); pdf.writeTo(bytes); pdf.close(); check(bytes.size() > 100)
                // Import an actual PDF page, annotate it, export the notebook and render the export.
                val sourcePdf = File(targetContext.cacheDir, "native-background-fixture.pdf")
                sourcePdf.writeBytes(bytes.toByteArray())
                val library = PdfLibrary(targetContext)
                val imported = library.importFrom(targetContext, android.net.Uri.fromFile(sourcePdf))
                val importedPages = library.buildPages(imported) { _, _ -> }
                ink.insertPagesAfterCurrent(importedPages)
                val pdfInk = Stroke(Brush.NATIVE_WIDTH, 0, 1f, floatArrayOf(ink.page.w / 2),
                    floatArrayOf(ink.page.h / 2), FloatArray(0), renderedWidths = floatArrayOf(28f))
                ink.page.items.add(pdfInk); ink.repaintAll()
                val exported = PdfExporter.export(targetContext, ink.note, library) { _, _ -> }
                check(exported.pages == 2 && exported.bytes > 100)
                targetContext.contentResolver.openFileDescriptor(exported.uri, "r")!!.use { descriptor ->
                    android.graphics.pdf.PdfRenderer(descriptor).use { reader ->
                        check(reader.pageCount == 2)
                        reader.openPage(1).use { outputPage ->
                            val output = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
                            outputPage.render(output, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            check(android.graphics.Color.red(output.getPixel(150, 150)) < 80) { "Native annotation absent from exported PDF" }
                            output.recycle()
                        }
                    }
                }
                targetContext.contentResolver.delete(exported.uri, null, null)
                library.close(); sourcePdf.delete()
                activity.finish()
            }
            waitForIdleSync()
            val reopened = launch(); waitForIdleSync()
            runOnMainSync {
                val again = field.get(reopened) as InkView
                check(again.note.pages[0].items.size == 2)
                check(again.note.pages.size == 2 && again.note.pages[1].isPdf)
                check((again.note.pages[1].items.single() as Stroke).renderedWidths!!.single() == 28f)
                val native = again.note.pages[0].items.first() as Stroke
                check(native.widthScale == 2f && native.renderedWidths!!.contentEquals(floatArrayOf(2f, 5f, 3f)))
                reopened.finish()
            }
            finish(0, Bundle().apply { putString("result", "PASS: idle retained frame, fallback, full erase/select/pan gestures, undo/redo, v8 reopen, width transform, bitmap, PDF import/annotation/export/reopen") })
        } catch (e: Throwable) { finish(1, Bundle().apply { putString("error", android.util.Log.getStackTraceString(e)) }) }
    }
}
