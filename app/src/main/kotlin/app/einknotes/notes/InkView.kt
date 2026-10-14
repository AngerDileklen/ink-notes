package app.einknotes.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pen options the user can change. Saved by the activity. */
class ToolSettings {
    var penWidth = 1.0f
    var penGray = 0
    var hlWidth = 1.0f
    var hlGray = 170
    var calWidth = 1.0f
    var calGray = 0
    var calAngle = (Math.PI / 2).toFloat()
    var calFlat = 0.75f
    /** Hold the pen still at the end of a stroke and it becomes a clean shape. */
    var shapeSnap = true
    var snapHoldMs = 600L
    /** Which shape the Shape tool draws. */
    var shapeTool = UserShape.RECT
    /** Eraser: remove whole strokes, or only the part under the eraser. */
    var eraserPartial = false
    var eraserRadius = 18f
    var textSize = 38f
    var textGray = 0
    var textBold = false
    /** Pen smoothing: 0 off, 1 light, 2 strong. */
    var smoothing = 0
}

/** How far the pen position moves toward the real position at each sample, for smoothing off / light / strong. */
private val SMOOTHING = floatArrayOf(1f, 0.55f, 0.3f)

enum class UserShape(val label: String) { LINE("Line"), ARROW("Arrow"), RECT("Rectangle"), ELLIPSE("Ellipse"), TRIANGLE("Triangle") }

/**
 * One notebook page on screen. Everything committed (paper, PDF, items) lives in a single bitmap that is
 * only rebuilt when the page, zoom, pan, erase, undo or a selection drag changes. New ink is drawn straight
 * onto that bitmap one small piece at a time, so each pen sample only repaints a few pixels. That keeps
 * e-ink updates small, which is what makes writing feel fast.
 */
class InkView(context: Context, val note: NoteData, private val pdfs: PdfLibrary, private val host: Host) : View(context) {

    interface Host {
        /** Pen touched the glass: switch the panel to a fast waveform. */
        fun onPenDown()
        /** Pen left the glass: switch back to a clean waveform soon. */
        fun onPenUp()
        /** Items, pages or paper changed: save, refresh labels and the undo buttons. */
        fun onDocumentChanged()
        /** The selection appeared, changed or went away. */
        fun onSelectionChanged()
        /** The Text tool was tapped at (x, y) in page units; [existing] is the text box under the pen, if any. */
        fun onTextRequested(x: Float, y: Float, existing: TextItem?)
    }

    // ---- state -------------------------------------------------------------------------------

    var pageIndex = 0
        private set
    var tool = Tool.PEN
    /** What the pen's side button does while it is held. */
    var sideButtonTool = Tool.ERASER
    val settings = ToolSettings()

    val page: PageData get() = note.pages[pageIndex]

    private val renderer = PageRenderer()
    private val zoomSteps = floatArrayOf(1f, 1.5f, 2f, 3f)
    var zoomIndex = 0
        private set
    private var fit = 1f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var bitmap: Bitmap? = null
    private var bmpCanvas: Canvas? = null

    // PDF background of the current page, rendered for the current view on a worker thread
    private val pdfExecutor = Executors.newSingleThreadExecutor()
    private var bgBitmap: Bitmap? = null
    private var bgKey = ""
    private var bgScale = 0f
    private var bgTx = 0f
    private var bgTy = 0f
    private var bgToken = 0

    // undo / redo
    private val undoStack = ArrayList<Cmd>()
    private val redoStack = ArrayList<Cmd>()
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    // the stroke being written right now
    private var brush = Brush.PEN
    private var painter: PageRenderer.StrokePainter? = null
    private var hlBase: Bitmap? = null
    private val liveXs = FloatList()
    private val liveYs = FloatList()
    private val livePs = FloatList()
    private var writing = false
    private var lastPressure = 0f
    private var lastVx = 0f
    private var lastVy = 0f

    // hold-to-snap
    private var lastMoveTime = 0L
    private var snapTried = false
    private var snappedShape: ShapeItem? = null
    private val holdCheck = object : Runnable {
        override fun run() {
            if (!writing) return
            checkHold()
            postDelayed(this, 120)
        }
    }

    // audio: items written while a recording runs remember which recording and when
    var recId = 0
    var recStart = 0L
    private var strokeMs = 0
    private fun recNow(): Int = if (recId > 0) (SystemClock.elapsedRealtime() - recStart).toInt() else 0
    private fun stamp(item: Item, ms: Int = recNow()) { if (recId > 0) { item.recId = recId; item.recMs = ms } }

    /** While a recording is replayed with "Replay" on, items of that recording written after [replayMs] stay hidden. */
    private var replayRec = 0
    private var replayMs = 0
    /** Tapping an item with the Hand tool while a recording plays jumps the audio to when it was written. */
    var onSeekItem: ((Item) -> Unit)? = null
    private var handDownX = 0f
    private var handDownY = 0f

    private fun futureItems(): List<Item>? =
        if (replayRec > 0) page.items.filter { it.recId == replayRec && it.recMs > replayMs } else null

    fun setReplay(rec: Int, ms: Int) {
        val before = futureItems()?.size ?: -1
        replayRec = rec; replayMs = ms
        val after = futureItems()?.size ?: -1
        if (before != after) rebuild()
    }

    // smoothing: the pen position is eased toward the real one
    private var smX = 0f
    private var smY = 0f

    // ruler: a straight edge you can move and turn; strokes that start beside it follow its edge
    var rulerOn = false
        set(v) { field = v; invalidate() }
    private var rcx = PAGE_W / 2f
    private var rcy = PAGE_H / 2f
    private var rAngle = 0f
    private val rLen = 1100f
    private val rHalf = 46f
    private var rGrab = 0                 // 0 none, 1 moving, 2 turning by the + end, 3 turning by the - end
    private var rSide = 0                 // +1 / -1 while a stroke follows the ruler's edge
    private var rDownX = 0f
    private var rDownY = 0f
    private var rStartX = 0f
    private var rStartY = 0f
    private var rLastDraw = 0L
    private var projX = 0f
    private var projY = 0f
    private val rulerPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f; isAntiAlias = true }
    private val rulerTick = Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(60, 60, 60); strokeWidth = 2f }

    // the Tape tool: drag out a patch, tap one to lift it
    private var tapeDown = false
    private var tapeX0 = 0f
    private var tapeY0 = 0f
    private var tapePreview: RectF? = null

    // the Shape tool: drag out a shape
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var previewShape: ShapeItem? = null
    private var previewRect: Rect? = null

    // the erase gesture being performed right now
    private var erasing = false
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private val erased = ArrayList<Pair<Int, Item>>()
    private var rebuildPending = false

    // hand (pan) gesture
    private var panning = false
    private var panLastX = 0f
    private var panLastY = 0f
    private var lastPanRebuild = 0L

    // selection
    val selection = ArrayList<Item>()
    val hasSelection get() = selection.isNotEmpty()
    private enum class SelMode { NONE, LASSO, MOVE, SCALE }
    private var selMode = SelMode.NONE
    private val lasso = Path()
    private val lassoPts = FloatList()
    private var selLayer: Bitmap? = null
    private val dragMatrix = Matrix()       // preview of the drag, in view coordinates
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var startDist = 1f
    private val selDash = Paint().apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f); isAntiAlias = false
    }
    private val handlePaint = Paint().apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val handleHole = Paint().apply { style = Paint.Style.FILL; color = Color.WHITE }

    /** Area drawn since the panel last got a "clean" pass, in view pixels. Used by the polish step. */
    private var polish: Rect? = null

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Until this time (uptimeMillis) "Virtual" pen input is treated as the firmware's fake page-turn swipe. */
    private var ignoreVirtualUntil = 0L

    /** Called when a volume button is pressed: the firmware then injects a fake swipe from a "Virtual" device. */
    fun ignoreVirtualFor(ms: Long) { ignoreVirtualUntil = SystemClock.uptimeMillis() + ms }

    fun describe(): String =
        "page=${pageIndex + 1}/${note.pages.size} items=${page.items.size} tool=$tool zoomIndex=$zoomIndex scale=${"%.3f".format(scale)} " +
            "paper=${page.paper} undo=${undoStack.size} redo=${redoStack.size} selected=${selection.size} view=${width}x${height}"

    // ---- page operations ---------------------------------------------------------------------

    fun goToPage(i: Int) {
        if (i !in note.pages.indices || i == pageIndex) return
        clearSelection()
        pageIndex = i
        fitToView(keepZoom = true)
        transformChanged()
        rebuild()
    }

    fun addPageAfterCurrent() {
        clearSelection()
        note.pages.add(pageIndex + 1, PageData(if (page.isPdf) Paper.BLANK else page.paper))
        pageIndex += 1
        fitToView(keepZoom = false)
        transformChanged()
        rebuild()
        host.onDocumentChanged()
    }

    /** Puts [list] after the current page and jumps to the first of them. */
    fun insertPagesAfterCurrent(list: List<PageData>) {
        if (list.isEmpty()) return
        clearSelection()
        note.pages.addAll(pageIndex + 1, list)
        pageIndex += 1
        fitToView(keepZoom = false)
        transformChanged()
        rebuild()
        host.onDocumentChanged()
    }

    /** Inserts [list] at [index] and shows the first of them. */
    fun insertPagesAt(index: Int, list: List<PageData>) {
        if (list.isEmpty()) return
        clearSelection()
        note.pages.addAll(index.coerceIn(0, note.pages.size), list)
        pageIndex = index.coerceIn(0, note.pages.size - 1)
        fitToView(keepZoom = false)
        transformChanged()
        rebuild()
        host.onDocumentChanged()
    }

    /** Deletes the current page (a lone page is replaced by an empty one). Its undo history goes with it. */
    fun deleteCurrentPage() = deletePageAt(pageIndex)

    fun deletePageAt(i: Int) {
        if (i !in note.pages.indices) return
        clearSelection()
        val removed = note.pages[i]
        if (note.pages.size == 1) note.pages[0] = PageData()
        else {
            note.pages.removeAt(i)
            if (i < pageIndex) pageIndex -= 1
            if (pageIndex >= note.pages.size) pageIndex = note.pages.size - 1
        }
        undoStack.removeAll { it.page === removed }
        redoStack.removeAll { it.page === removed }
        fitToView(keepZoom = false)
        transformChanged()
        rebuild()
        host.onDocumentChanged()
    }

    /** Moves page [from] so that it ends up at position [to]. The page you are looking at stays in view. */
    fun movePage(from: Int, to: Int) {
        if (from !in note.pages.indices || to !in note.pages.indices || from == to) return
        val current = page
        val p = note.pages.removeAt(from)
        note.pages.add(to, p)
        pageIndex = note.pages.indexOf(current)
        host.onDocumentChanged()
    }

    /** A full copy of page [i] (background and everything on it), placed right after it. */
    fun duplicatePage(i: Int) {
        if (i !in note.pages.indices) return
        val src = note.pages[i]
        val copy = PageData(src.paper)
        copy.w = src.w; copy.h = src.h; copy.pdfFile = src.pdfFile; copy.pdfPage = src.pdfPage
        for (item in src.items) copy.items.add(item.copy())
        note.pages.add(i + 1, copy)
        if (i < pageIndex) pageIndex += 1
        host.onDocumentChanged()
    }

    /** An empty page right after page [i]. */
    fun insertBlankAfter(i: Int) {
        if (i !in note.pages.indices) return
        note.pages.add(i + 1, PageData(if (note.pages[i].isPdf) Paper.BLANK else note.pages[i].paper))
        if (i < pageIndex) pageIndex += 1
        host.onDocumentChanged()
    }

    fun setPaper(p: Paper) {
        page.paper = p
        rebuild()
        host.onDocumentChanged()
    }

    /** Fit -> 1.5x -> 2x -> 3x -> back to Fit. */
    fun cycleZoom() { zoomBy(if (zoomIndex >= zoomSteps.size - 1) -zoomIndex else 1) }

    fun zoomLabel(): String = when (zoomIndex) { 0 -> "Fit"; 1 -> "1.5x"; 2 -> "2x"; else -> "3x" }

    fun zoomBy(step: Int) {
        val next = (zoomIndex + step).coerceIn(0, zoomSteps.size - 1)
        if (next == zoomIndex) return
        // keep the page point under the middle of the view fixed
        val cx = width / 2f; val cy = height / 2f
        val px = (cx - tx) / scale; val py = (cy - ty) / scale
        zoomIndex = next
        scale = fit * zoomSteps[zoomIndex]
        tx = cx - px * scale; ty = cy - py * scale
        clampOffsets()
        transformChanged()
        rebuild()
    }

    fun panBy(dxFraction: Float, dyFraction: Float) {
        tx -= dxFraction * width; ty -= dyFraction * height
        clampOffsets()
        transformChanged()
        rebuild()
    }

    // ---- undo / redo -------------------------------------------------------------------------

    fun undo() {
        val cmd = undoStack.removeLastOrNull() ?: return
        clearSelection()
        when (cmd) {
            is Cmd.Add -> for (added in cmd.items) cmd.page.items.removeAll { x -> x === added }
            is Cmd.Remove -> for ((idx, item) in cmd.removed.asReversed()) cmd.page.items.add(min(idx, cmd.page.items.size), item)
            is Cmd.Transform -> { val inv = Matrix(); cmd.m.invert(inv); for (moved in cmd.items) moved.transform(inv) }
            is Cmd.Custom -> cmd.undoAction()
        }
        redoStack.add(cmd)
        showPageOf(cmd)
    }

    fun redo() {
        val cmd = redoStack.removeLastOrNull() ?: return
        clearSelection()
        when (cmd) {
            is Cmd.Add -> cmd.page.items.addAll(cmd.items)
            is Cmd.Remove -> for ((idx, _) in cmd.removed) if (idx < cmd.page.items.size) cmd.page.items.removeAt(idx)
            is Cmd.Transform -> for (moved in cmd.items) moved.transform(cmd.m)
            is Cmd.Custom -> cmd.redoAction()
        }
        undoStack.add(cmd)
        showPageOf(cmd)
    }

    private fun push(cmd: Cmd) { undoStack.add(cmd); redoStack.clear() }

    /** For changes made outside the canvas (for example a bookmark): save and refresh the labels. */
    fun markChanged() { host.onDocumentChanged() }

    /** The area that needs a clean repaint after fast writing, or null. Resets it. */
    fun takePolishRect(): Rect? { val r = polish; polish = null; return r }

    fun repaintAll() { rebuild() }

    // ---- selection actions -------------------------------------------------------------------

    fun clearSelection() {
        if (selection.isEmpty() && selMode == SelMode.NONE) return
        val wasDragging = selMode == SelMode.MOVE || selMode == SelMode.SCALE
        selection.clear(); selMode = SelMode.NONE; selLayer?.recycle(); selLayer = null
        lasso.reset(); lassoPts.clear()
        if (wasDragging) rebuild() else invalidate()
        host.onSelectionChanged()
    }

    fun selectAll() {
        clearSelection()
        selection.addAll(page.items)
        rebuild(); host.onSelectionChanged()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removed = ArrayList<Pair<Int, Item>>()
        for (item in selection.toList()) {
            val idx = page.items.indexOfFirst { it === item }
            if (idx >= 0) { removed.add(idx to item); page.items.removeAt(idx) }
        }
        selection.clear()
        if (removed.isNotEmpty()) { push(Cmd.Remove(page, removed)); host.onDocumentChanged() }
        rebuild(); host.onSelectionChanged()
    }

    /** Copies of the selected items, for the clipboard. */
    fun copySelection(): List<Item> = selection.map { it.copy() }

    /** Adds [items] to the page (shifted a little so they do not hide the originals) and selects them. */
    fun paste(items: List<Item>, offset: Float = 40f) {
        if (items.isEmpty()) return
        clearSelection()
        val fresh = items.map { it.copy() }
        val m = Matrix().apply { setTranslate(offset, offset) }
        for (it in fresh) it.transform(m)
        page.items.addAll(fresh)
        push(Cmd.Add(page, fresh))
        selection.addAll(fresh)
        host.onDocumentChanged(); rebuild(); host.onSelectionChanged()
    }

    fun duplicateSelection() { paste(copySelection()) }

    // ---- layout / transform ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmpCanvas = Canvas(bitmap!!)
        fitToView(keepZoom = true)
        transformChanged()
        rebuild()
    }

    private fun fitToView(keepZoom: Boolean) {
        if (width <= 0 || height <= 0) return
        fit = min(width / page.w, height / page.h)
        if (!keepZoom) zoomIndex = 0
        scale = fit * zoomSteps[zoomIndex]
        tx = 0f; ty = 0f
        clampOffsets()
    }

    private fun clampOffsets() {
        val pw = page.w * scale; val ph = page.h * scale
        tx = if (pw <= width) (width - pw) / 2f else tx.coerceIn(width - pw, 0f)
        ty = if (ph <= height) (height - ph) / 2f else ty.coerceIn(height - ph, 0f)
    }

    private fun showPageOf(cmd: Cmd) {
        val idx = note.pages.indexOf(cmd.page)
        if (idx >= 0 && idx != pageIndex) { pageIndex = idx; fitToView(keepZoom = true); transformChanged() }
        rebuild()
        host.onDocumentChanged()
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // dragged selection preview
        val layer = selLayer
        if (layer != null && (selMode == SelMode.MOVE || selMode == SelMode.SCALE)) canvas.drawBitmap(layer, dragMatrix, null)
        // selection frame and handles
        if (selection.isNotEmpty() && selMode != SelMode.LASSO) {
            val b = selectionBoundsView()
            if (b != null) {
                if (selMode == SelMode.MOVE || selMode == SelMode.SCALE) dragMatrix.mapRect(b)
                canvas.drawRect(b, selDash)
                for ((hx, hy) in corners(b)) {
                    canvas.drawRect(hx - 15f, hy - 15f, hx + 15f, hy + 15f, handlePaint)
                    canvas.drawRect(hx - 9f, hy - 9f, hx + 9f, hy + 9f, handleHole)
                }
            }
        }
        if (selMode == SelMode.LASSO) canvas.drawPath(lasso, selDash)
        previewShape?.let { renderer.drawItem(canvas, it, scale, tx, ty) }
        tapePreview?.let { canvas.drawRect(it, selDash) }
        if (rulerOn) drawRuler(canvas)
    }

    private fun drawRuler(c: Canvas) {
        c.save()
        c.translate(tx + rcx * scale, ty + rcy * scale)
        c.rotate(Math.toDegrees(rAngle.toDouble()).toFloat())
        val hl = rLen / 2f * scale; val hh = rHalf * scale
        c.drawRect(-hl, -hh, hl, hh, rulerPaint)
        var k = 0
        var x = -rLen / 2f
        while (x <= rLen / 2f + 0.5f) {
            val len = if (k % 5 == 0) 20f else 10f
            val vx = x * scale
            c.drawLine(vx, -hh, vx, -hh + len, rulerTick)
            c.drawLine(vx, hh, vx, hh - len, rulerTick)
            x += 20f; k++
        }
        // the two ends are for turning the ruler
        for (s in intArrayOf(-1, 1)) c.drawCircle(s * (hl - 56f * scale), 0f, 12f * scale.coerceAtLeast(0.6f), rulerPaint)
        c.restore()
    }

    private fun rulerZone(vx: Float, vy: Float): Int {
        val px = (vx - tx) / scale - rcx; val py = (vy - ty) / scale - rcy
        val ca = cos(rAngle); val sa = sin(rAngle)
        val t = px * ca + py * sa
        val d = -px * sa + py * ca
        if (kotlin.math.abs(t) > rLen / 2f + 40f / scale) return 0
        if (kotlin.math.abs(d) <= rHalf) return if (kotlin.math.abs(t) > rLen / 2f - 120f / scale) (if (t > 0) 2 else 3) else 1
        if (kotlin.math.abs(d) - rHalf <= 70f / scale) return if (d > 0) 4 else 5
        return 0
    }

    private fun projectToRuler(vx: Float, vy: Float) {
        val px = (vx - tx) / scale - rcx; val py = (vy - ty) / scale - rcy
        val ca = cos(rAngle); val sa = sin(rAngle)
        val t = (px * ca + py * sa).coerceIn(-rLen / 2f, rLen / 2f)
        val qx = rcx + ca * t - sa * rSide * rHalf
        val qy = rcy + sa * t + ca * rSide * rHalf
        projX = qx * scale + tx; projY = qy * scale + ty
    }

    private fun beginRulerGrab(zone: Int, vx: Float, vy: Float) {
        rGrab = if (zone == 1) 1 else zone
        rDownX = vx; rDownY = vy; rStartX = rcx; rStartY = rcy
    }

    private fun moveRuler(vx: Float, vy: Float) {
        if (rGrab == 1) {
            rcx = rStartX + (vx - rDownX) / scale; rcy = rStartY + (vy - rDownY) / scale
        } else {
            val px = (vx - tx) / scale - rcx; val py = (vy - ty) / scale - rcy
            var a = atan2(py, px) + if (rGrab == 3) Math.PI.toFloat() else 0f
            // turn in steps of 15 degrees when close to one
            val step = (Math.PI / 12).toFloat()
            val nearest = (a / step).roundToInt() * step
            if (kotlin.math.abs(a - nearest) < 0.05f) a = nearest
            rAngle = a
        }
        val now = SystemClock.uptimeMillis()
        if (now - rLastDraw > 60) { rLastDraw = now; invalidate() }
    }

    /** Redraws paper, PDF and every item into the bitmap, then repaints the whole view. */
    private fun rebuild() {
        val c = bmpCanvas ?: return
        rebuildPending = false
        c.drawColor(Color.WHITE)
        if (page.isPdf) {
            val b = bgBitmap
            // A background rendered for an earlier pan position is still useful: slide it into place until the sharp one arrives.
            if (b != null && bgKey == "${page.pdfFile}#${page.pdfPage}" && bgScale == scale) c.drawBitmap(b, tx - bgTx, ty - bgTy, null)
            renderer.drawBorder(c, page, scale, tx, ty)
        } else renderer.drawPaper(c, page, scale, tx, ty)
        // while the selection is being dragged it is drawn as a separate layer
        val dragged = if (selMode == SelMode.MOVE || selMode == SelMode.SCALE) selection else null
        val future = futureItems()
        val hide = if (future == null) dragged else if (dragged == null) future else future + dragged
        renderer.drawItems(c, page, scale, tx, ty, hide)
        invalidate()
    }

    private fun scheduleRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        postDelayed({ if (rebuildPending) rebuild() }, 90)
    }

    /** The page moved, zoomed or changed: the PDF background needs rendering again. */
    private fun transformChanged() {
        if (!page.isPdf) return
        val name = page.pdfFile ?: return
        if (width <= 0 || height <= 0) return
        val token = ++bgToken
        val key = "$name#${page.pdfPage}"
        val w = width; val h = height; val sc = scale; val x = tx; val y = ty; val pw = page.w; val idx = page.pdfPage
        pdfExecutor.execute {
            val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { null }
            val t0 = SystemClock.uptimeMillis()
            val ok = bmp != null && pdfs.render(name, idx, bmp, pw, sc, x, y)
            val ms = SystemClock.uptimeMillis() - t0
            post {
                if (token == bgToken && ok && bmp != null) {
                    val old = bgBitmap
                    bgBitmap = bmp; bgKey = key; bgScale = sc; bgTx = x; bgTy = y
                    if (debuggable) Log.d("InkView", "pdf page $idx rendered in $ms ms (${w}x$h)")
                    if (old !== bmp) old?.recycle()
                    rebuild()
                } else if (bmp != null) bmp.recycle()
            }
        }
    }

    // ---- input -------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (debuggable && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP)) {
            Log.d("InkView", "touch action=${e.actionMasked} tool=${e.getToolType(0)} deviceId=${e.deviceId} " +
                "dev=${e.device?.name} source=0x${Integer.toHexString(e.source)} p=${e.pressure} xy=${e.x.toInt()},${e.y.toInt()}")
        }
        // The firmware injects a fake swipe (device "Virtual") right after a volume button. Never draw that one.
        // Other "Virtual" pen input is real: when an accessibility service is active, Android relabels the real pen.
        val virtual = e.device?.name == "Virtual" || e.deviceId < 0
        if (virtual && SystemClock.uptimeMillis() < ignoreVirtualUntil) return true
        val toolType = e.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) return true   // this tablet has no finger touch; ignore strays

        val active = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            toolType == 6 -> sideButtonTool                          // pen tool type while the side button is held
            else -> tool
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.onPenDown()
                lastVx = e.x; lastVy = e.y
                when (active) {
                    Tool.PEN, Tool.HIGHLIGHTER, Tool.CALLIGRAPHY -> {
                        clearSelection()
                        val zone = if (rulerOn) rulerZone(e.x, e.y) else 0
                        when {
                            zone in 1..3 -> beginRulerGrab(zone, e.x, e.y)
                            zone >= 4 -> {
                                rSide = if (zone == 4) 1 else -1
                                projectToRuler(e.x, e.y)
                                beginStroke(active, projX, projY, e.pressure)
                                lastVx = projX; lastVy = projY
                            }
                            else -> beginStroke(active, e.x, e.y, e.pressure)
                        }
                    }
                    Tool.ERASER -> {
                        clearSelection(); erasing = true; erased.clear(); eraseLastX = e.x; eraseLastY = e.y
                        eraseBefore = page.items.toList(); partialDirty = false
                        eraseAt(e.x, e.y)
                    }
                    Tool.HAND -> { panning = true; panLastX = e.x; panLastY = e.y; handDownX = e.x; handDownY = e.y }
                    Tool.TAPE -> { clearSelection(); tapeDown = true; tapeX0 = e.x; tapeY0 = e.y; tapePreview = null }
                    Tool.SELECT -> beginSelectGesture(e.x, e.y)
                    Tool.TEXT -> { clearSelection(); textDown = true; textDownX = e.x; textDownY = e.y }
                    Tool.SHAPE -> { clearSelection(); shapeStartX = e.x; shapeStartY = e.y; previewShape = null; previewRect = null; shapeDragging = true }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) move(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
                move(e.x, e.y, e.pressure)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when {
                    writing -> {
                        // The lift-off position is part of the stroke: without it every line ends a little short.
                        if (e.actionMasked == MotionEvent.ACTION_UP && snappedShape == null) {
                            for (i in 0 until e.historySize) move(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalPressure(i))
                            if (rSide != 0) { projectToRuler(e.x, e.y); finalPoint(projX, projY) } else finalPoint(e.x, e.y)
                        }
                        endStroke()
                    }
                    rGrab != 0 -> { rGrab = 0; invalidate() }
                    tapeDown -> endTape(e.x, e.y, e.actionMasked == MotionEvent.ACTION_UP)
                    erasing -> endErase()
                    textDown -> {
                        textDown = false
                        if (hypot(e.x - textDownX, e.y - textDownY) < 16f && e.actionMasked == MotionEvent.ACTION_UP) requestText(e.x, e.y)
                    }
                    shapeDragging -> endShapeDrag()
                    panning -> {
                        panning = false
                        val seek = onSeekItem
                        if (seek != null && e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - handDownX, e.y - handDownY) < 16f) {
                            val px = (e.x - tx) / scale; val py = (e.y - ty) / scale
                            page.items.lastOrNull { it.recId > 0 && it.near(px, py, 16f / scale) && it.hit(px, py, 16f / scale) }?.let(seek)
                        }
                        transformChanged(); rebuild()
                    }
                    selMode != SelMode.NONE -> endSelectGesture(e.x, e.y)
                }
                host.onPenUp()
            }
        }
        return true
    }

    private var shapeDragging = false
    private var textDown = false
    private var textDownX = 0f
    private var textDownY = 0f

    private fun move(x: Float, y: Float, rawPressure: Float) {
        when {
            rGrab != 0 -> moveRuler(x, y)
            tapeDown -> dragTape(x, y)
            writing -> {
                if (snappedShape != null) {
                    // A snap is only a proposal: if the pen moves away again, the person was not finished.
                    if (hypot(x - lastVx, y - lastVy) > 25f) cancelSnap(x, y, rawPressure)
                } else if (rSide != 0) { projectToRuler(x, y); addStrokePoint(projX, projY, rawPressure) }
                else addStrokePoint(x, y, rawPressure)
            }
            tool == Tool.SHAPE && !erasing && !panning && selMode == SelMode.NONE && shapeDragging -> dragShape(x, y)
            erasing -> eraseAlong(x, y)
            panning -> {
                tx += x - panLastX; ty += y - panLastY
                panLastX = x; panLastY = y
                clampOffsets()
                val now = System.currentTimeMillis()
                if (now - lastPanRebuild > 140) { lastPanRebuild = now; rebuild() }
            }
            selMode != SelMode.NONE -> moveSelectGesture(x, y)
        }
    }

    // ---- writing -----------------------------------------------------------------------------

    private fun beginStroke(active: Tool, x: Float, y: Float, rawPressure: Float) {
        val c = bmpCanvas ?: return
        brush = when (active) { Tool.HIGHLIGHTER -> Brush.HIGHLIGHTER; Tool.CALLIGRAPHY -> Brush.CALLIGRAPHY; else -> Brush.PEN }
        liveXs.clear(); liveYs.clear(); livePs.clear()
        writing = true
        strokeMs = recNow(); smX = x; smY = y
        snappedShape = null; snapTried = false; lastMoveTime = SystemClock.uptimeMillis()
        removeCallbacks(holdCheck); postDelayed(holdCheck, 120)
        lastPressure = normalisePressure(rawPressure)
        if (brush == Brush.HIGHLIGHTER) {
            // Highlighter is redrawn as one path each time; keep a clean copy to restore underneath it.
            hlBase = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
            painter = null
        } else {
            painter = renderer.newPainter(c, brush, grayFor(brush), widthFor(brush), settings.calAngle, settings.calFlat, scale)
        }
        pushPoint(x, y, lastPressure)
        flushLive()
    }

    private fun grayFor(b: Brush) = when (b) { Brush.HIGHLIGHTER -> settings.hlGray; Brush.CALLIGRAPHY -> settings.calGray; else -> settings.penGray }
    private fun widthFor(b: Brush) = when (b) { Brush.HIGHLIGHTER -> settings.hlWidth; Brush.CALLIGRAPHY -> settings.calWidth; else -> settings.penWidth }

    private fun addStrokePoint(x: Float, y: Float, rawPressure: Float) {
        // Ignore jitter: samples closer than one pixel add nothing but cost time.
        val dx = x - lastVx; val dy = y - lastVy
        if (dx * dx + dy * dy < 1f) return
        lastVx = x; lastVy = y
        lastMoveTime = SystemClock.uptimeMillis(); snapTried = false
        lastPressure = 0.6f * lastPressure + 0.4f * normalisePressure(rawPressure)   // gentle pressure smoothing
        val a = if (rSide != 0) 1f else SMOOTHING[settings.smoothing.coerceIn(0, SMOOTHING.size - 1)]
        smX += a * (x - smX); smY += a * (y - smY)
        pushPoint(smX, smY, lastPressure)
        flushLive()
    }

    /** Adds the pen-up position using the last known pressure (a lifted pen reports zero). */
    private fun finalPoint(x: Float, y: Float) {
        val dx = x - lastVx; val dy = y - lastVy
        if (dx * dx + dy * dy < 0.25f) return
        lastVx = x; lastVy = y; smX = x; smY = y
        pushPoint(x, y, lastPressure)
        flushLive()
    }

    private fun pushPoint(x: Float, y: Float, p: Float) {
        liveXs.add((x - tx) / scale); liveYs.add((y - ty) / scale); livePs.add(p)
        painter?.add(x, y, p)
    }

    /** Repaints just the little area the newest pieces of ink touched. */
    private fun flushLive() {
        if (brush == Brush.HIGHLIGHTER) { updateHighlighter(); return }
        val d = painter?.dirty ?: return
        painter?.dirty = null
        invalidate(d)
        polish = polish?.also { it.union(d) } ?: Rect(d)
    }

    private fun updateHighlighter() {
        val c = bmpCanvas ?: return
        val base = hlBase ?: return
        val xs = liveXs.toArray(); val ys = liveYs.toArray()
        val path = renderer.highlighterPath(xs, ys, xs.size, scale, tx, ty)
        val b = RectF(); path.computeBounds(b, true)
        val widthPx = HIGHLIGHTER_WIDTH * settings.hlWidth * scale
        val pad = (widthPx / 2f).toInt() + 4
        val region = Rect(b.left.toInt() - pad, b.top.toInt() - pad, b.right.toInt() + pad, b.bottom.toInt() + pad)
        region.intersect(0, 0, base.width, base.height)
        c.drawBitmap(base, region, region, null)                    // restore what was under the stroke so far
        renderer.drawHighlighter(c, path, settings.hlGray, widthPx, xs.size == 1)
        invalidate(region)
        polish = polish?.also { it.union(region) } ?: Rect(region)
    }

    private fun endStroke() {
        removeCallbacks(holdCheck)
        val snapped = snappedShape
        if (snapped == null) { painter?.finish(); flushLive() }   // a replaced stroke must not draw any more ink
        painter = null
        writing = false
        rSide = 0
        hlBase?.recycle(); hlBase = null
        if (snapped != null) {
            snappedShape = null
            stamp(snapped, strokeMs)
            page.items.add(snapped)
            push(Cmd.Add(page, listOf(snapped)))
            host.onDocumentChanged()
            return
        }
        if (liveXs.size >= 1) {
            val s = Stroke(brush, grayFor(brush), widthFor(brush), liveXs.toArray(), liveYs.toArray(), livePs.toArray(),
                settings.calAngle, settings.calFlat)
            stamp(s, strokeMs)
            page.items.add(s)
            push(Cmd.Add(page, listOf(s)))
            host.onDocumentChanged()
        }
    }

    // ---- text and images ---------------------------------------------------------------------

    private fun requestText(vx: Float, vy: Float) {
        val px = (vx - tx) / scale; val py = (vy - ty) / scale
        val existing = page.items.lastOrNull { it is TextItem && it.hit(px, py, 8f / scale) } as? TextItem
        host.onTextRequested(px, py, existing)
    }

    fun addText(text: String, x: Float, y: Float) {
        if (text.isBlank()) return
        val w = min(760f, page.w - x - 30f).coerceAtLeast(180f)
        val item = TextItem(text, x, y, w, settings.textSize, settings.textGray, settings.textBold)
        stamp(item)
        page.items.add(item)
        push(Cmd.Add(page, listOf(item)))
        selection.clear(); selection.add(item)
        host.onDocumentChanged(); rebuild(); host.onSelectionChanged()
    }

    /** Replaces the words in a text box (an empty text removes the box). */
    fun updateText(item: TextItem, newText: String) {
        val old = item.text
        if (newText == old) return
        if (newText.isBlank()) { selection.clear(); selection.add(item); deleteSelection(); return }
        item.text = newText
        push(Cmd.Custom(page, { item.text = old }, { item.text = newText }))
        host.onDocumentChanged(); rebuild(); host.onSelectionChanged()
    }

    fun selectedText(): TextItem? = selection.singleOrNull() as? TextItem

    fun insertImage(img: ImageStore.Imported) {
        val maxW = page.w * 0.6f
        val w = min(maxW, img.width.toFloat()); val h = w * img.height / img.width
        val cx = ((width / 2f - tx) / scale).coerceIn(w / 2f, page.w - w / 2f)
        val cy = ((height / 2f - ty) / scale).coerceIn(h / 2f, page.h - h / 2f)
        val item = ImageItem(img.name, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, img.width, img.height)
        stamp(item)
        page.items.add(item)
        push(Cmd.Add(page, listOf(item)))
        selection.clear(); selection.add(item)
        host.onDocumentChanged(); rebuild(); host.onSelectionChanged()
    }

    // ---- shapes ------------------------------------------------------------------------------

    /** The pen has been still long enough: if the stroke looks like a shape, replace it with the clean one. */
    private fun checkHold() {
        if (!settings.shapeSnap || snappedShape != null || snapTried) return
        if (brush == Brush.HIGHLIGHTER || rSide != 0) return
        if (SystemClock.uptimeMillis() - lastMoveTime < settings.snapHoldMs) return
        snapTried = true
        if (liveXs.size < 6) return
        val d = ShapeDetector.detect(liveXs.toArray(), liveYs.toArray(), scale) ?: return
        val shape = ShapeItem(d.kind, d.pts, d.closed, grayFor(brush), widthFor(brush))
        snappedShape = shape
        if (debuggable) Log.d("InkView", "snapped to ${d.label} (confidence ${"%.2f".format(d.confidence)})")
        // redraw the page without the rough stroke, then show the clean shape in its place
        rebuild()
        bmpCanvas?.let { renderer.drawItem(it, shape, scale, tx, ty) }
        invalidate()
    }

    /** Takes the clean shape back and carries on drawing the freehand stroke from where it was. */
    private fun cancelSnap(x: Float, y: Float, rawPressure: Float) {
        snappedShape = null
        val c = bmpCanvas ?: return
        rebuild()
        val p = renderer.newPainter(c, brush, grayFor(brush), widthFor(brush), settings.calAngle, settings.calFlat, scale)
        for (i in 0 until liveXs.size) p.add(tx + liveXs[i] * scale, ty + liveYs[i] * scale, livePs[i])
        painter = p
        flushLive()
        addStrokePoint(x, y, rawPressure)
    }

    private fun shapeFor(kind: UserShape, x0: Float, y0: Float, x1: Float, y1: Float): ShapeItem? {
        // convert from view pixels to page units
        val ax = (x0 - tx) / scale; val ay = (y0 - ty) / scale; val bx = (x1 - tx) / scale; val by = (y1 - ty) / scale
        val l = min(ax, bx); val r = max(ax, bx); val t = min(ay, by); val b = max(ay, by)
        val g = settings.penGray; val w = settings.penWidth
        return when (kind) {
            UserShape.LINE -> ShapeItem(ShapeKind.LINE, floatArrayOf(ax, ay, bx, by), false, g, w)
            UserShape.ARROW -> ShapeItem(ShapeKind.ARROW, floatArrayOf(ax, ay, bx, by), false, g, w)
            UserShape.RECT -> ShapeItem(ShapeKind.POLYGON, floatArrayOf(l, t, r, t, r, b, l, b), true, g, w)
            UserShape.TRIANGLE -> ShapeItem(ShapeKind.POLYGON, floatArrayOf((l + r) / 2f, t, r, b, l, b), true, g, w)
            UserShape.ELLIPSE -> {
                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                ShapeItem(ShapeKind.ELLIPSE, floatArrayOf(cx, cy, r, cy, cx, b), true, g, w)
            }
        }
    }

    private fun dragShape(x: Float, y: Float) {
        val s = shapeFor(settings.shapeTool, shapeStartX, shapeStartY, x, y) ?: return
        val old = previewRect
        previewShape = s
        val region = Rect(
            (tx + s.minX * scale).toInt() - 12, (ty + s.minY * scale).toInt() - 12,
            (tx + s.maxX * scale).toInt() + 12, (ty + s.maxY * scale).toInt() + 12)
        previewRect = region
        invalidate(old?.let { Rect(it).apply { union(region) } } ?: region)
    }

    private fun endShapeDrag() {
        shapeDragging = false
        val s = previewShape
        previewShape = null
        val region = previewRect; previewRect = null
        if (s != null && (s.maxX - s.minX) * scale > 14f || s != null && (s.maxY - s.minY) * scale > 14f) {
            stamp(s)
            page.items.add(s)
            push(Cmd.Add(page, listOf(s)))
            host.onDocumentChanged()
            if (region != null) polish = polish?.also { it.union(region) } ?: Rect(region)
        }
        rebuild()
    }

    // ---- tape --------------------------------------------------------------------------------

    private fun dragTape(x: Float, y: Float) {
        val old = tapePreview
        val r = RectF(min(tapeX0, x), min(tapeY0, y), max(tapeX0, x), max(tapeY0, y))
        tapePreview = r
        val region = Rect(r.left.toInt() - 6, r.top.toInt() - 6, r.right.toInt() + 6, r.bottom.toInt() + 6)
        if (old != null) region.union(Rect(old.left.toInt() - 6, old.top.toInt() - 6, old.right.toInt() + 6, old.bottom.toInt() + 6))
        invalidate(region)
    }

    private fun endTape(x: Float, y: Float, up: Boolean) {
        tapeDown = false
        tapePreview = null
        if (!up) { invalidate(); return }
        val px0 = (tapeX0 - tx) / scale; val py0 = (tapeY0 - ty) / scale; val px1 = (x - tx) / scale; val py1 = (y - ty) / scale
        if (hypot(x - tapeX0, y - tapeY0) < 24f) {
            // a tap lifts the tape under the pen, or puts it back
            val tape = page.items.lastOrNull { it is TapeItem && it.hit(px1, py1, 6f / scale) } as? TapeItem
            if (tape != null) {
                val now = !tape.covered
                tape.covered = now
                push(Cmd.Custom(page, { tape.covered = !now }, { tape.covered = now }))
                host.onDocumentChanged()
            }
            rebuild()
            return
        }
        val l = min(px0, px1); val r = max(px0, px1); val t = min(py0, py1); val b = max(py0, py1)
        if (r - l < 20f || b - t < 12f) { rebuild(); return }
        val item = TapeItem(l, t, r, b, true)
        stamp(item)
        page.items.add(item)
        push(Cmd.Add(page, listOf(item)))
        host.onDocumentChanged()
        rebuild()
    }

    // ---- erasing -----------------------------------------------------------------------------

    /** Erases along the straight path from the previous sample, so fast movement cannot jump over a stroke. */
    private fun eraseAlong(vx: Float, vy: Float) {
        val dx = vx - eraseLastX; val dy = vy - eraseLastY
        val dist = sqrt(dx * dx + dy * dy)
        val steps = max(1, (dist / 8f).toInt())
        for (i in 1..steps) eraseAt(eraseLastX + dx * i / steps, eraseLastY + dy * i / steps)
        eraseLastX = vx; eraseLastY = vy
    }

    private var eraseBefore: List<Item> = emptyList()
    private var partialDirty = false

    /** Partial mode: cut out only the part of each stroke under the eraser; other items are removed whole. */
    private fun partialAt(px: Float, py: Float, r: Float) {
        val list = page.items
        var changed = false
        for (i in list.indices.reversed()) {
            val item = list[i]
            if (item is Stroke) {
                val frags = item.eraseCircle(px, py, r) ?: continue
                list.removeAt(i); list.addAll(i, frags); changed = true
            } else if (item.near(px, py, r) && item.hit(px, py, r)) { list.removeAt(i); changed = true }
        }
        if (changed) { partialDirty = true; scheduleRebuild() }
    }

    private fun eraseAt(vx: Float, vy: Float) {
        val px = (vx - tx) / scale; val py = (vy - ty) / scale
        val r = settings.eraserRadius / scale
        if (settings.eraserPartial) { partialAt(px, py, r); return }
        val list = page.items
        var removedAny = false
        for (i in list.indices.reversed()) {
            val s = list[i]
            if (s.near(px, py, r) && s.hit(px, py, r)) {
                erased.add(i to s)
                list.removeAt(i)
                removedAny = true
            }
        }
        if (removedAny) scheduleRebuild()
    }

    private fun endErase() {
        erasing = false
        if (partialDirty) {
            val before = eraseBefore; val after = page.items.toList(); val pg = page
            push(Cmd.Custom(pg, { pg.items.clear(); pg.items.addAll(before) }, { pg.items.clear(); pg.items.addAll(after) }))
            partialDirty = false
            rebuild()
            host.onDocumentChanged()
            return
        }
        if (erased.isNotEmpty()) {
            push(Cmd.Remove(page, erased.toList()))
            erased.clear()
            rebuild()
            host.onDocumentChanged()
        }
    }

    // ---- selecting, moving and resizing ------------------------------------------------------

    private fun selectionBoundsView(): RectF? {
        if (selection.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (it in selection) { l = min(l, it.minX); t = min(t, it.minY); r = max(r, it.maxX); b = max(b, it.maxY) }
        return RectF(tx + l * scale - 6f, ty + t * scale - 6f, tx + r * scale + 6f, ty + b * scale + 6f)
    }

    private fun corners(b: RectF) = listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)

    private fun beginSelectGesture(x: Float, y: Float) {
        dragStartX = x; dragStartY = y
        val b = selectionBoundsView()
        if (b != null) {
            // grabbing a corner resizes (the opposite corner stays put)
            val cs = corners(b)
            for ((i, c) in cs.withIndex()) {
                if (hypot(x - c.first, y - c.second) < 45f) {
                    val opp = cs[(i + 2) % 4]
                    anchorX = opp.first; anchorY = opp.second
                    startDist = max(hypot(x - anchorX, y - anchorY), 1f)
                    startDrag(SelMode.SCALE)
                    return
                }
            }
            // grabbing inside the frame moves everything
            if (b.contains(x, y)) { startDrag(SelMode.MOVE); return }
        }
        // otherwise start a new lasso
        clearSelection()
        selMode = SelMode.LASSO
        lasso.reset(); lasso.moveTo(x, y)
        lassoPts.clear(); lassoPts.add(x); lassoPts.add(y)
    }

    private fun startDrag(mode: SelMode) {
        selMode = mode
        dragMatrix.reset()
        // render the selected items once into their own layer so dragging is just moving a bitmap
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(layer)
        for (it in selection) renderer.drawItem(c, it, scale, tx, ty)
        selLayer = layer
        rebuild()
    }

    private fun moveSelectGesture(x: Float, y: Float) {
        when (selMode) {
            SelMode.LASSO -> {
                lasso.lineTo(x, y); lassoPts.add(x); lassoPts.add(y)
                invalidate()
            }
            SelMode.MOVE -> { dragMatrix.setTranslate(x - dragStartX, y - dragStartY); invalidate() }
            SelMode.SCALE -> {
                val f = (hypot(x - anchorX, y - anchorY) / startDist).coerceIn(0.2f, 6f)
                dragMatrix.setScale(f, f, anchorX, anchorY); invalidate()
            }
            SelMode.NONE -> { }
        }
    }

    private fun endSelectGesture(x: Float, y: Float) {
        when (selMode) {
            SelMode.LASSO -> {
                val pts = lassoPts.toArray()
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                for (i in pts.indices step 2) { l = min(l, pts[i]); r = max(r, pts[i]); t = min(t, pts[i + 1]); b = max(b, pts[i + 1]) }
                selection.clear()
                if (r - l < 14f && b - t < 14f) {
                    // a tap: pick the topmost item under the pen
                    val px = (x - tx) / scale; val py = (y - ty) / scale
                    page.items.lastOrNull { it.near(px, py, 14f / scale) && it.hit(px, py, 14f / scale) }?.let { selection.add(it) }
                } else {
                    val poly = FloatArray(pts.size)
                    for (i in pts.indices step 2) { poly[i] = (pts[i] - tx) / scale; poly[i + 1] = (pts[i + 1] - ty) / scale }
                    for (it in page.items) if (it.fractionInside(poly) >= 0.5f) selection.add(it)
                }
                selMode = SelMode.NONE
                lasso.reset(); lassoPts.clear()
                invalidate(); host.onSelectionChanged()
            }
            SelMode.MOVE, SelMode.SCALE -> {
                // apply the previewed drag to the real items, in page coordinates
                val vm = Matrix(dragMatrix)
                val toView = Matrix().apply { setScale(scale, scale); postTranslate(tx, ty) }
                val toPage = Matrix(); toView.invert(toPage)
                val pm = Matrix(toView); pm.postConcat(vm); pm.postConcat(toPage)    // page -> view -> dragged -> page
                if (!vm.isIdentity) {
                    for (it in selection) it.transform(pm)
                    push(Cmd.Transform(page, selection.toList(), pm))
                    host.onDocumentChanged()
                }
                selMode = SelMode.NONE
                selLayer?.recycle(); selLayer = null
                rebuild(); host.onSelectionChanged()
            }
            SelMode.NONE -> { }
        }
    }
}
