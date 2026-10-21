package app.einknotes.notes

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.ViewTreeObserver
import app.einknotes.EinkController
import dev.hanvon.ink.*

/** App policy only. Firmware calls, packet conversion and brush rendering live in the SDK. */
class NativeInkController(private val activity: Activity, private val view: InkView,
    private val eink: EinkController, private val excluded: () -> InkRect?, private val blocked: () -> Boolean) {
    val capability = HanvonInk.probe(activity)
    private var generation = 0L
    private var session: InkSession? = null
    private var resumed = false
    private var failed = false
    private var heldTool = false
    private var transition = false
    private var refreshing = false
    private var redrawRevision = 0L
    private var active = false
    private var configKey = ""
    private val preDraw = ViewTreeObserver.OnPreDrawListener { sync(); true }
    init {
        view.onNativeInvalidated = {
            val owner = session
            if (active) { owner?.suspendDrawing(); active = false; view.nativeDrawing = false }
            val revision = ++redrawRevision
            if (resumed && owner != null && view.hasWindowFocus() && !activity.isInMultiWindowMode) {
                transition = true
                view.afterRetainedRedraw {
                    if (revision == redrawRevision && session === owner && resumed) {
                        owner.notifyRetainedRedrawCommitted {
                            if (revision == redrawRevision && session === owner && resumed) {
                                transition = false; sync()
                            }
                        }
                    }
                }
            } else transition = false
        }
        view.viewTreeObserver.addOnPreDrawListener(preDraw)
    }
    fun resume() { resumed = true; failed = false; sync() }
    fun pause() {
        generation++; redrawRevision++; resumed = false; refreshing = false; heldTool = false
        active = false; view.nativeDrawing = false
        view.invalidateNative(); session?.close(); session = null; view.nativeSession = null
        eink.nativeOwnership(false)
    }
    fun destroy() {
        pause(); view.onNativeInvalidated = null
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }
    fun beforeInput(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val tool = view.inputTool(event) ?: return // Ignored palms must not cancel a native handoff.
            heldTool = tool != Tool.PEN
            if (heldTool) view.invalidateNative()
            sync()
        }
    }
    fun afterInput(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            heldTool = false; view.post { sync() }
        }
    }
    fun genericInput(event: MotionEvent) {
        val signals = PenInput.classify(event.getToolType(0), event.buttonState, event.source)
        if (signals.tool == PenSignal.FINGER && !view.fingerPan) return
        heldTool = signals.primary || signals.secondary || signals.tertiary ||
            signals.tool == PenSignal.REAR_ERASER || signals.tool == PenSignal.FINGER || signals.tool == PenSignal.PEN_AS_FINGER
        sync()
    }
    fun refresh() {
        val owner = generation
        refreshing = true
        view.invalidateNative(); session?.suspendDrawing(); active = false; view.nativeDrawing = false
        eink.fullRefresh { if (owner == generation) { refreshing = false; sync() } }
    }
    fun sync() {
        val eligible = resumed && !failed && capability.available && !activity.isInMultiWindowMode &&
            !blocked() && !heldTool && !transition && !refreshing && view.nativeEligible && view.width > 0 && view.height > 0 && view.hasWindowFocus()
        if (!eligible) {
            if (!resumed || activity.isInMultiWindowMode || !view.hasWindowFocus()) session?.setForeground(false)
            if (active) {
                active = false; view.nativeDrawing = false; session?.suspendDrawing(); view.invalidateNative()
            }
            eink.nativeOwnership(false)
            if (!capability.available) view.nativeStatus = "Android ink: ${capability.reason}"
            else if (!failed) view.nativeStatus = "Android ink: native paused for current tool, settings or window"
            return
        }
        if (session == null) {
            try {
                val owner = ++generation
                session = HanvonInk.attach(view, InkConfig(widthPx = view.nativeWidth, brush = view.nativeBrush), object : InkListener {
                    override fun onRenderedPacket(stroke: RenderedStroke, arrivalNanos: Long) {
                        if (owner == generation && active) view.receiveNative(stroke, arrivalNanos)
                    }
                    override fun onGeometryChanged() {
                        if (owner == generation) view.invalidateNative()
                    }
                    override fun onState(state: SessionState, detail: String) {
                        if (owner != generation) return
                        view.nativeDrawing = active && state in setOf(SessionState.READY, SessionState.DRAWING)
                        if (state == SessionState.FAILED) {
                            failed = true; view.nativeStatus = "Android ink: native failed ($detail)"; pause()
                        } else if (active) view.nativeStatus = "Native ${view.nativeBrush.name.lowercase()}: $state"
                    }
                    override fun onDiagnostic(operation: String, detail: String, elapsedNanos: Long) {
                        Log.d("NativeInk", "$operation: $detail")
                    }
                })
                view.nativeSession = session; configKey = ""
            } catch (e: Exception) { failed = true; view.nativeStatus = "Android ink: ${e.message}"; return }
        }
        val exclusion = excluded()
        val key = "${view.display?.rotation}:${view.nativeWidth}:${view.nativeBrush}:${view.nativeArea}:$exclusion"
        if (!active || key != configKey) {
            if (active && key != configKey) { view.invalidateNative(); return }
            eink.nativeOwnership(true)
            session!!.suspendDrawing()
            session!!.setBrush(view.nativeBrush); session!!.setWidth(view.nativeWidth)
            session!!.setBounds(view.nativeArea, exclusion)
            configKey = key; active = true
            session!!.setForeground(true); session!!.resumeDrawing()
        }
    }
}
