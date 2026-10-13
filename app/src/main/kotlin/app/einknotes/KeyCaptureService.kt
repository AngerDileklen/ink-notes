package app.einknotes

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Hands raw key events (including the nine hardware buttons) to whoever is listening. */
object KeyBus {
    @Volatile var connected = false
    /** When true the service swallows keys so the system does not also act on them (never the power key). */
    @Volatile var consume = false
    @Volatile var sink: ((KeyEvent) -> Unit)? = null
}

/**
 * Accessibility services are the standard way for an app to see key presses before the system's own
 * window manager handles them. It reads keys only; it never reads screen content.
 */
class KeyCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        KeyBus.connected = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        KeyBus.sink?.invoke(event)
        return KeyBus.consume && event.keyCode != KeyEvent.KEYCODE_POWER
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onUnbind(intent: Intent?): Boolean {
        KeyBus.connected = false
        return super.onUnbind(intent)
    }
}
