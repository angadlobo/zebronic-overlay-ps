package com.padoverlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * Multi-finger touch injector built on AccessibilityService.dispatchGesture.
 *
 * Every held "finger" is a stroke with willContinue = true, so it stays pressed.
 * Any change (new press, move, release) is sent as ONE gesture that continues every
 * held stroke, so pressing ✕ never lifts your thumb off the stick. Updates are
 * coalesced: while a gesture is in flight we only remember the newest state and
 * send it the moment the previous one finishes (about a frame of latency).
 */
class Injector(private val svc: AccessibilityService) {

    private class Finger(var x: Float, var y: Float) {
        val startX = x
        val startY = y
        var stroke: StrokeDescription? = null
        var sentX = 0f
        var sentY = 0f
        var released = false
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var nextId = 1
    private var inFlight = false
    private var dirty = false
    private val handler = Handler(Looper.getMainLooper())

    fun down(x: Float, y: Float): Int {
        val id = nextId++
        fingers[id] = Finger(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        kick()
        return id
    }

    fun move(id: Int, x: Float, y: Float) {
        val f = fingers[id] ?: return
        val nx = x.coerceAtLeast(0f)
        val ny = y.coerceAtLeast(0f)
        // Ignore stick noise: each gesture sent delays the next one, including button presses.
        if (abs(nx - f.x) < MIN_MOVE_PX && abs(ny - f.y) < MIN_MOVE_PX) return
        f.x = nx
        f.y = ny
        kick()
    }

    fun up(id: Int) {
        val f = fingers[id] ?: return
        f.released = true
        kick()
    }

    fun releaseAll() {
        if (fingers.isEmpty()) return
        fingers.values.forEach { it.released = true }
        kick()
    }

    private fun kick() {
        dirty = true
        if (!inFlight) flush()
    }

    private fun flush() {
        dirty = false
        if (fingers.isEmpty()) return
        val builder = GestureDescription.Builder()
        val finished = ArrayList<Int>()
        var count = 0
        for ((id, f) in fingers) {
            if (count >= MAX_FINGERS) break
            val prev = f.stroke
            val path = Path()
            val stroke = if (prev == null) {
                // Start where the finger first landed, even if it has already moved while another
                // gesture was in flight: otherwise a stick is grabbed off-centre and doesn't deflect.
                path.moveTo(f.startX, f.startY)
                if (f.startX != f.x || f.startY != f.y) path.lineTo(f.x, f.y)
                if (f.released) StrokeDescription(path, 0, TAP_MS, false)
                else StrokeDescription(path, 0, STEP_MS, true)
            } else {
                path.moveTo(f.sentX, f.sentY)
                if (f.sentX != f.x || f.sentY != f.y) path.lineTo(f.x, f.y)
                prev.continueStroke(path, 0, STEP_MS, !f.released)
            }
            builder.addStroke(stroke)
            count++
            f.stroke = stroke
            f.sentX = f.x
            f.sentY = f.y
            if (f.released) finished += id
        }
        finished.forEach { fingers.remove(it) }
        inFlight = svc.dispatchGesture(builder.build(), callback, handler)
        if (!inFlight) fingers.clear()
    }

    private val callback = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            inFlight = false
            if (dirty) flush()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // The system lifted every finger. Press the ones still held again.
            inFlight = false
            fingers.values.forEach { it.stroke = null }
            if (fingers.isNotEmpty()) flush()
        }
    }

    companion object {
        private const val STEP_MS = 4L
        private const val MIN_MOVE_PX = 2f
        private const val TAP_MS = 40L
        private val MAX_FINGERS = GestureDescription.getMaxStrokeCount()
    }
}
