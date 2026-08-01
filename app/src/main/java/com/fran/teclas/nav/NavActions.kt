package com.fran.teclas.nav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.util.Log

/**
 * The only thing on the device that can press back, home or recents on our behalf.
 *
 * An overlay can draw a gesture bar, but nothing an ordinary app can call performs a system Back —
 * `performGlobalAction` on a connected [AccessibilityService] is it. So the launcher's existing
 * [com.fran.teclas.InputInjectionService] hands itself to this object when the framework connects it
 * and takes itself back on teardown, and the overlay talks to the service only through here.
 *
 * A direct reference rather than the broadcast the docked keyboard uses for its own injection: a
 * broadcast round-trip lands somewhere between 10 and 50ms after the finger lifts, which is a
 * visible stutter on a gesture the user expects to feel instant. Held as a plain volatile field
 * cleared on teardown — never a leak, because an accessibility service that is not connected has
 * already put itself in the field's place with null.
 */
internal object NavActions {
    private const val TAG = "NavActions"

    @Volatile
    private var service: AccessibilityService? = null

    fun attach(target: AccessibilityService) {
        service = target
    }

    fun detach(target: AccessibilityService) {
        if (service === target) service = null
    }

    fun service(): AccessibilityService? = service

    fun isReady(): Boolean = service != null

    /**
     * Perform [gesture]. Returns false when the accessibility service is not connected, which is the
     * signal the settings screen uses to tell the user the overlay will draw but do nothing.
     */
    fun perform(gesture: NavGesture): Boolean {
        val target = service ?: return false
        val action = when (gesture) {
            NavGesture.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            NavGesture.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            NavGesture.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            NavGesture.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            // AOSP exposes no quick-switch action to accessibility services — the recents animation
            // it rides on is private to the system's own launcher. Recents is the honest
            // approximation: one extra tap, and it never lands on the wrong app.
            NavGesture.SWITCH_APP -> AccessibilityService.GLOBAL_ACTION_RECENTS
            NavGesture.NONE -> return false
        }
        return runCatching { target.performGlobalAction(action) }
            .onFailure { Log.w(TAG, "global action $gesture failed", it) }
            .getOrDefault(false)
    }

    /** Synthesise a touch. Used only to replay a tap the overlay strip swallowed. */
    fun dispatch(
        description: GestureDescription,
        callback: AccessibilityService.GestureResultCallback?,
        handler: Handler?,
    ): Boolean {
        val target = service ?: return false
        return runCatching { target.dispatchGesture(description, callback, handler) }
            .onFailure { Log.w(TAG, "dispatchGesture failed", it) }
            .getOrDefault(false)
    }
}
