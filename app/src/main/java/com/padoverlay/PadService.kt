package com.padoverlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class PadService : AccessibilityService() {

    companion object {
        @Volatile var instance: PadService? = null
            private set
    }

    lateinit var prefs: Prefs
    lateinit var injector: Injector
    lateinit var gamepad: Gamepad
    private lateinit var wm: WindowManager
    val density: Float get() = resources.displayMetrics.density

    private var controls: MutableList<Control> = mutableListOf()
    private var sw = 1
    private var sh = 1
    private var landscape = true
    private var inTargetApp = false
    private var touchHidden = false

    private val touchViews = mutableListOf<View>()
    private var handle: View? = null
    private var catcher: View? = null
    private var editor: FrameLayout? = null
    private var editorView: EditorView? = null

    /** True when presses should be turned into taps (overlay hidden, in Remote Play). */
    val playing: Boolean get() = editor == null && shouldShow()

    private fun shouldShow() = prefs.alwaysShow || inTargetApp

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        prefs = Prefs(this)
        injector = Injector(this)
        gamepad = Gamepad(this, injector)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        refresh()
    }

    override fun onDestroy() {
        instance = null
        if (::wm.isInitialized) { removeAll(); removeEditor() }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (editor != null) closeEditor(save = true) else refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || isKeyboard(pkg)) return
        val now = pkg in prefs.packageSet()
        if (now != inTargetApp) {
            inTargetApp = now
            refresh()
        }
    }

    private fun isKeyboard(pkg: String): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return ime.startsWith("$pkg/")
    }

    /** Gamepad buttons arrive here even while Remote Play has focus. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::gamepad.isInitialized) return false
        if (!playing && editor == null && gamepad.learn == null) return false
        return gamepad.onKey(event)
    }

    // ---------- geometry ----------

    private fun measure() {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        }
        sw = p.x; sh = p.y
        landscape = sw >= sh
        controls = prefs.loadLayout(landscape)
    }

    fun px(c: Control) = c.tx * sw
    fun py(c: Control) = c.ty * sh
    fun controlFor(key: Int) = controls.firstOrNull { it.enabled && it.kind == Kind.BUTTON && it.key == key }
    fun controlById(id: String) = controls.firstOrNull { it.enabled && it.id == id }

    private fun params(w: Int, h: Int, x: Int, y: Int, focusable: Boolean = false) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
             else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x; this.y = y
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    /** Add a window at a screen position, then nudge it if the system offset it (status bar, notch). */
    private fun addAt(v: View, w: Int, h: Int, sx: Int, sy: Int, focusable: Boolean = false) {
        val lp = params(w, h, sx, sy, focusable)
        wm.addView(v, lp)
        v.post {
            if (v.parent == null) return@post
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val dx = sx - loc[0]; val dy = sy - loc[1]
            if (dx != 0 || dy != 0) {
                lp.x += dx; lp.y += dy
                try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
        }
    }

    private fun safeRemove(v: View?) { if (v?.parent != null) try { wm.removeView(v) } catch (_: Exception) {} }

    // ---------- play mode ----------

    fun refresh() {
        if (!::wm.isInitialized) return
        removeAll()
        if (editor != null) { addCatcher(); return }
        gamepad.releaseAll()
        if (!shouldShow()) return
        measure()
        addCatcher()
        if (prefs.touchButtons && !touchHidden) addTouchButtons()
        addHandle()
    }

    private fun removeAll() {
        touchViews.forEach { safeRemove(it) }; touchViews.clear()
        safeRemove(handle); handle = null
        safeRemove(catcher); catcher = null
    }

    private fun addCatcher() {
        if (!prefs.analog || catcher != null) return
        val v = CatcherView(this, this)
        addAt(v, 1, 1, 0, 0, focusable = true)
        v.post { v.requestFocus() }
        catcher = v
    }

    private fun addTouchButtons() {
        val alpha = prefs.opacity / 100f
        for (c in controls) {
            if (!c.enabled) continue
            val size = (c.sizeDp * density).toInt()
            val v = ControlView(this, c, this).apply { this.alpha = alpha }
            addAt(v, size, size, (c.x * sw).toInt() - size / 2, (c.y * sh).toInt() - size / 2)
            touchViews += v
        }
    }

    private fun addHandle() {
        val size = (40 * density).toInt()
        lateinit var lp: WindowManager.LayoutParams
        val v = HandleView(this, onTap = { openEditor() }, onMoved = { rx, ry ->
            lp.x = (rx - size / 2).coerceIn(0, sw - size)
            lp.y = (ry - size / 2).coerceIn(0, sh - size)
            prefs.handleX = lp.x / sw.toFloat(); prefs.handleY = lp.y / sh.toFloat()
            handle?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
        })
        v.alpha = 0.8f
        val x = (prefs.handleX * sw).toInt().coerceIn(0, sw - size)
        val y = (prefs.handleY * sh).toInt().coerceIn(0, sh - size)
        lp = params(size, size, x, y)
        wm.addView(v, lp)
        handle = v
    }

    /** Called on any controller press: hide the overlay and start playing. */
    fun onControllerUsed() {
        if (editor != null && gamepad.learn == null) {
            closeEditor(save = true)
        } else if (prefs.touchButtons && !touchHidden && touchViews.isNotEmpty()) {
            touchHidden = true
            touchViews.forEach { safeRemove(it) }; touchViews.clear()
        }
    }

    // ---------- mapping overlay ----------

    fun openEditor() {
        if (editor != null || !::wm.isInitialized) return
        gamepad.releaseAll()
        removeAll()
        measure()
        val ev = EditorView(this, controls, sw, sh, prefs.touchButtons)
        val root = FrameLayout(this)
        root.addView(ev, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = buildToolbar(ev)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        wm.addView(root, params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0))
        editor = root
        editorView = ev
        addCatcher()
    }

    fun closeEditor(save: Boolean) {
        gamepad.learn = null
        if (save) prefs.saveLayout(landscape, controls)
        removeEditor()
        touchHidden = false
        refresh()
    }

    private fun removeEditor() {
        safeRemove(editor); editor = null; editorView = null
    }

    private fun buildToolbar(ev: EditorView): View {
        val d = density
        val info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            setOnClickListener { onClick() }
            row.addView(this)
        }

        fun describe() {
            val c = ev.selected
            info.text = when {
                ev.learning != null -> "Press the controller button for ${ev.learning!!.label}…"
                c == null -> "Drag each ✛ onto Remote Play's button. Tap one to bind it. Press any controller button to play."
                c.kind == Kind.STICK -> "${c.label}: drag ✛ to the stick centre. The dashed ring is how far it pushes."
                else -> "${c.label} → ${keyName(c.key)}. Tap Bind, then press a controller button."
            }
        }
        ev.onSelect = { describe(); ev.invalidate() }

        fun selected(action: (Control) -> Unit) { ev.selected?.let { action(it); ev.invalidate(); describe() } }

        btn("Bind") {
            selected { c ->
                if (c.kind == Kind.STICK) return@selected
                ev.learning = c
                gamepad.learn = { code ->
                    controls.forEach { if (it !== c && it.key == code) it.key = 0 } // one key → one control
                    c.key = code
                    ev.learning = null
                    ev.invalidate(); describe()
                }
            }
        }
        btn("Unbind") { selected { it.key = 0 } }
        btn("−") { selected { if (it.kind == Kind.STICK) it.rangeDp = (it.rangeDp - 5).coerceAtLeast(10f) else it.sizeDp = (it.sizeDp - 6).coerceAtLeast(30f) } }
        btn("+") { selected { if (it.kind == Kind.STICK) it.rangeDp = (it.rangeDp + 5).coerceAtMost(200f) else it.sizeDp = (it.sizeDp + 6).coerceAtMost(160f) } }
        btn("Remove") { selected { it.enabled = false } }
        btn("Restore all") { controls.forEach { it.enabled = true }; ev.invalidate() }
        btn("⇅") {
            val bar = row.parent.parent as View
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (lp.gravity and Gravity.TOP == Gravity.TOP) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                         else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bar.layoutParams = lp
        }
        btn("Cancel") { closeEditor(save = false) }
        btn("Save ✓") { closeEditor(save = true) }

        describe()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.argb(200, 15, 20, 35)); cornerRadius = 12 * d }
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            addView(info)
            addView(HorizontalScrollView(this@PadService).apply { addView(row) })
        }
    }
}
