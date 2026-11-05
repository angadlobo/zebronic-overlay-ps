package com.padoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

private fun textPaint(sizePx: Float, color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color; textSize = sizePx; textAlign = Paint.Align.CENTER
    setShadowLayer(3f, 0f, 1f, Color.BLACK)
}

private fun Canvas.label(text: String, x: Float, y: Float, p: Paint) =
    drawText(text, x, y - (p.descent() + p.ascent()) / 2f, p)

/** Optional on-screen touch button / stick (only shown when "touch buttons" is on). */
@SuppressLint("ViewConstructor")
class ControlView(ctx: Context, private val c: Control, private val svc: PadService) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE
    }
    private val text = textPaint(16 * d)
    private var finger = -1
    private var kx = 0f
    private var ky = 0f

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        if (c.kind == Kind.STICK) {
            fill.color = Color.argb(60, 255, 255, 255)
            canvas.drawCircle(r, r, r - d, fill); canvas.drawCircle(r, r, r - d, ring)
            fill.color = if (finger >= 0) Color.argb(220, 120, 200, 255) else Color.argb(160, 255, 255, 255)
            canvas.drawCircle(r + kx * r * 0.55f, r + ky * r * 0.55f, r * 0.4f, fill)
        } else {
            fill.color = if (finger >= 0) Color.argb(220, 120, 200, 255) else Color.argb(110, 30, 30, 30)
            canvas.drawCircle(r, r, r - d, fill); canvas.drawCircle(r, r, r - d, ring)
            canvas.label(c.label, r, r, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inj = svc.injector
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (svc.prefs.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                finger = inj.down(svc.px(c), svc.py(c))
                if (c.kind == Kind.STICK) drag(e)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (c.kind == Kind.STICK) drag(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                inj.up(finger); finger = -1; kx = 0f; ky = 0f; invalidate()
            }
        }
        return true
    }

    private fun drag(e: MotionEvent) {
        val r = width / 2f
        var dx = (e.x - r) / (r * 0.7f)
        var dy = (e.y - r) / (r * 0.7f)
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        kx = dx; ky = dy
        val range = c.rangeDp * d
        svc.injector.move(finger, svc.px(c) + dx * range, svc.py(c) + dy * range)
        invalidate()
    }
}

/** Small draggable bubble. Tap = overlay on/off. Hold = edit the mapping. Drag = move it. */
@SuppressLint("ViewConstructor")
class HandleView(
    ctx: Context,
    private val active: Boolean,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: (Int, Int) -> Unit,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (active) Color.argb(190, 30, 130, 70) else Color.argb(150, 70, 70, 70)
    }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 60, 60); strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND
    }
    private val text = textPaint(18 * d)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress()
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r, fill)
        canvas.label("🎮", r, r, text)
        if (!active) canvas.drawLine(r * 0.4f, r * 0.4f, r * 1.6f, r * 1.6f, slash)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > 10 * d) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging && !longPressed) onMoved(e.rawX.toInt(), e.rawY.toInt())
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragging && !longPressed) onTap()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }
}

/**
 * Invisible 1×1 window that holds input focus so we receive the gamepad's analog
 * sticks, triggers and hat d-pad (accessibility services only get buttons).
 */
@SuppressLint("ViewConstructor")
class CatcherView(ctx: Context, private val svc: PadService) : View(ctx) {
    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Deliver stick movement as it happens rather than batching it to the next frame.
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(InputDevice.SOURCE_JOYSTICK)
    }

    override fun onGenericMotionEvent(e: MotionEvent) = svc.gamepad.onMotion(e) || super.onGenericMotionEvent(e)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (svc.gamepad.onKey(e)) return true
        // Our window holds focus, so pass the phone's Back key on to the system.
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}

/**
 * Full-screen mapping overlay, drawn on top of Remote Play.
 *  - Amber ✛ = where we tap (drag it onto Remote Play's matching button).
 *  - Circle = your optional on-screen button (only when touch buttons are on).
 */
@SuppressLint("ViewConstructor")
class EditorView(
    ctx: Context,
    private val controls: MutableList<Control>,
    private val sw: Int,
    private val sh: Int,
    private val showButtons: Boolean,
) : View(ctx) {
    var selected: Control? = null
        private set
    var onSelect: (Control?) -> Unit = {}
    var learning: Control? = null

    private val d = resources.displayMetrics.density
    private val loc = IntArray(2)
    private val amber = Color.rgb(255, 190, 40)
    private val targetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val rangeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = amber
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; color = Color.argb(150, 255, 255, 255) }
    private val big = textPaint(15 * d)
    private val small = textPaint(11 * d, amber)
    private var drag: Control? = null
    private var dragTarget = false
    private val targetR get() = 16 * d

    private fun ox(): Float { getLocationOnScreen(loc); return loc[0].toFloat() }
    private fun oy(): Float = loc[1].toFloat()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.argb(70, 0, 0, 0))
        val ox = ox(); val oy = oy()
        for (c in controls) {
            if (!c.enabled) continue
            val tx = c.tx * sw - ox; val ty = c.ty * sh - oy
            val sel = c === selected
            val col = when {
                c === learning -> Color.rgb(80, 220, 120)
                sel -> Color.rgb(90, 200, 255)
                else -> amber
            }
            if (showButtons) {
                val bx = c.x * sw - ox; val by = c.y * sh - oy
                val r = c.sizeDp * d / 2
                canvas.drawLine(bx, by, tx, ty, line)
                fill.color = when {
                    covers(c) -> Color.argb(170, 220, 40, 40)
                    sel -> Color.argb(170, 40, 140, 220)
                    else -> Color.argb(120, 40, 40, 40)
                }
                canvas.drawCircle(bx, by, r, fill)
                canvas.label(c.label, bx, by, big)
            }
            targetRing.color = col
            if (c.kind == Kind.STICK) canvas.drawCircle(tx, ty, c.rangeDp * d, rangeRing)
            canvas.drawCircle(tx, ty, targetR, targetRing)
            canvas.drawLine(tx - targetR, ty, tx + targetR, ty, targetRing)
            canvas.drawLine(tx, ty - targetR, tx, ty + targetR, targetRing)
            small.color = col
            val keyText = if (c.kind == Kind.STICK) "stick" else keyName(c.key)
            canvas.label("${c.label} · $keyText", tx, ty + targetR + 10 * d, small)
        }
    }

    /** True if this on-screen button sits on top of any tap target (it would block the tap). */
    private fun covers(c: Control): Boolean {
        val bx = c.x * sw; val by = c.y * sh; val r = c.sizeDp * d / 2
        return controls.any { it.enabled && hypot(it.tx * sw - bx, it.ty * sh - by) < r + 6 * d }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = null
                // Targets first (they're what you mostly adjust), then your buttons.
                for (c in controls.asReversed()) {
                    if (!c.enabled) continue
                    if (hypot(e.rawX - c.tx * sw, e.rawY - c.ty * sh) < targetR * 1.8f) { drag = c; dragTarget = true; break }
                }
                if (drag == null && showButtons) for (c in controls.asReversed()) {
                    if (!c.enabled) continue
                    if (hypot(e.rawX - c.x * sw, e.rawY - c.y * sh) < c.sizeDp * d / 2) { drag = c; dragTarget = false; break }
                }
                selected = drag
                onSelect(selected)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> drag?.let {
                val fx = (e.rawX / sw).coerceIn(0f, 1f)
                val fy = (e.rawY / sh).coerceIn(0f, 1f)
                if (dragTarget) { it.tx = fx; it.ty = fy } else { it.x = fx; it.y = fy }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = null
        }
        return true
    }
}
