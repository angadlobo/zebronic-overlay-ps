package com.padoverlay

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Turns gamepad keys / axes into held touches on Remote Play's on-screen controls. */
class Gamepad(private val svc: PadService, private val injector: Injector) {

    /** When set, the next gamepad button press is handed here instead of being played. */
    var learn: ((Int) -> Unit)? = null

    private val heldKeys = HashMap<Int, Int>()      // key code -> finger id
    private val heldSticks = HashMap<String, Int>() // control id -> finger id
    private var hatX = 0
    private var hatY = 0
    private var l2 = false
    private var r2 = false

    fun releaseAll() {
        heldKeys.clear(); heldSticks.clear()
        hatX = 0; hatY = 0; l2 = false; r2 = false
        injector.releaseAll()
    }

    private fun isGamepad(source: Int) =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    /** Returns true if the key was consumed. */
    fun onKey(e: KeyEvent): Boolean {
        if (!isGamepad(e.source) && !KeyEvent.isGamepadButton(e.keyCode)) return false
        // Phone keys that some pads also send — never swallow these unless bound.
        if (e.keyCode == KeyEvent.KEYCODE_BACK && learn == null && svc.controlFor(e.keyCode) == null) return false
        if (e.repeatCount > 0) return true
        return press(e.keyCode, e.action == KeyEvent.ACTION_DOWN)
    }

    private fun press(code: Int, down: Boolean): Boolean {
        learn?.let { cb ->
            if (down) { learn = null; cb(code) }
            return true
        }
        if (down) svc.onControllerUsed()
        if (!svc.playing) return true
        val c = svc.controlFor(code) ?: return true
        if (down) {
            if (!heldKeys.containsKey(code)) heldKeys[code] = injector.down(svc.px(c), svc.py(c))
        } else {
            heldKeys.remove(code)?.let { injector.up(it) }
        }
        return true
    }

    fun onMotion(e: MotionEvent): Boolean {
        if (!isGamepad(e.source) || e.action != MotionEvent.ACTION_MOVE) return false
        val dev = e.device

        // D-pad reported as a hat axis
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X).roundToInt()
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y).roundToInt()
        if (hx != hatX) {
            if (hatX < 0) press(KeyEvent.KEYCODE_DPAD_LEFT, false)
            if (hatX > 0) press(KeyEvent.KEYCODE_DPAD_RIGHT, false)
            hatX = hx
            if (hx < 0) press(KeyEvent.KEYCODE_DPAD_LEFT, true)
            if (hx > 0) press(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        }
        if (hy != hatY) {
            if (hatY < 0) press(KeyEvent.KEYCODE_DPAD_UP, false)
            if (hatY > 0) press(KeyEvent.KEYCODE_DPAD_DOWN, false)
            hatY = hy
            if (hy < 0) press(KeyEvent.KEYCODE_DPAD_UP, true)
            if (hy > 0) press(KeyEvent.KEYCODE_DPAD_DOWN, true)
        }

        // Analog triggers
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)) > 0.5f
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)) > 0.5f
        if (lt != l2) { l2 = lt; press(KeyEvent.KEYCODE_BUTTON_L2, lt) }
        if (rt != r2) { r2 = rt; press(KeyEvent.KEYCODE_BUTTON_R2, rt) }

        // Sticks
        stick("ls", axis(e, dev, MotionEvent.AXIS_X), axis(e, dev, MotionEvent.AXIS_Y))
        val (ax, ay) = if (svc.prefs.rightStickRx) MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
                       else MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        stick("rs", axis(e, dev, ax), axis(e, dev, ay))
        return true
    }

    private fun axis(e: MotionEvent, dev: InputDevice?, axis: Int): Float {
        val range = dev?.getMotionRange(axis, e.source)
        val v = e.getAxisValue(axis)
        val flat = maxOf(range?.flat ?: 0f, DEAD_ZONE)
        return if (abs(v) < flat) 0f else v
    }

    private fun stick(id: String, x: Float, rawY: Float) {
        val y = if (svc.prefs.swapSticksY) -rawY else rawY
        val active = hypot(x, y) > 0f
        if (active && heldSticks[id] == null) svc.onControllerUsed()
        if (!svc.playing) return
        val c = svc.controlById(id) ?: return
        val fid = heldSticks[id]
        if (!active) {
            if (fid != null) { injector.up(fid); heldSticks.remove(id) }
            return
        }
        val cx = svc.px(c); val cy = svc.py(c)
        val r = c.rangeDp * svc.density
        val tx = cx + x.coerceIn(-1f, 1f) * r
        val ty = cy + y.coerceIn(-1f, 1f) * r
        if (fid == null) {
            val nf = injector.down(cx, cy)   // touch the stick's centre first…
            heldSticks[id] = nf
            injector.move(nf, tx, ty)         // …then drag in the stick direction
        } else {
            injector.move(fid, tx, ty)
        }
    }

    companion object { private const val DEAD_ZONE = 0.15f }
}
