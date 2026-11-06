package com.padoverlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class PadService : AccessibilityService() {

    companion object {
        @Volatile var instance: PadService? = null
            private set
        private val SYSTEM_BUTTONS = mapOf("create" to "Create", "options" to "Options", "ps" to "PS")
        private val SCAN_CODES = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 304, KeyEvent.KEYCODE_BUTTON_B to 305,
            KeyEvent.KEYCODE_BUTTON_X to 307, KeyEvent.KEYCODE_BUTTON_Y to 308,
            KeyEvent.KEYCODE_BUTTON_L1 to 310, KeyEvent.KEYCODE_BUTTON_R1 to 311,
            KeyEvent.KEYCODE_BUTTON_L2 to 312, KeyEvent.KEYCODE_BUTTON_R2 to 313,
            KeyEvent.KEYCODE_BUTTON_SELECT to 314, KeyEvent.KEYCODE_BUTTON_START to 315,
            KeyEvent.KEYCODE_BUTTON_MODE to 316,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 317, KeyEvent.KEYCODE_BUTTON_THUMBR to 318,
        )
        private const val PANEL_BUTTON = "Use the PS button and other buttons."
        private const val PANEL_OPEN_MS = 250L
        private const val REVEAL_MS = 150L
    }

    private var nativeController = false
    private var helperRunning = false // the PS controller helper answered; it may be switched off
    private val main = Handler(Looper.getMainLooper())
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { syncHelper(); refresh() }
        override fun onInputDeviceRemoved(id: Int) { syncHelper(); refresh() }
        override fun onInputDeviceChanged(id: Int) {}
    }

    /** The helper reads raw key codes, so it's told these rather than Android's key codes. */
    private fun touchScanCodes(): List<Int> = (prefs.loadLayout(true) + prefs.loadLayout(false))
        .filter { it.id in SYSTEM_BUTTONS }
        .mapNotNull { SCAN_CODES[it.key] }
        .distinct()

    /** Tell the helper (if any) whether the controller should be on, and note if it's there. */
    private fun syncHelper() {
        val message = "${if (prefs.active) '1' else '0'} ${touchScanCodes().joinToString(",")}\n"
        thread {
            val alive = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", Bridge.CONTROL_PORT), 300)
                    s.soTimeout = 500
                    s.getOutputStream().write(message.toByteArray())
                    s.getInputStream().read() >= 0
                }
            } catch (_: Exception) { false }
            main.post { if (alive != helperRunning) { helperRunning = alive; refresh() } }
        }
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
    private val overlayNeeded get() = !nativeController && !helperRunning

    val playing: Boolean get() = editor == null && overlayNeeded && prefs.active && shouldShow()

    private fun shouldShow() = prefs.alwaysShow || inTargetApp

    /** Turn the whole overlay on or off (the bubble stays so it can be turned back on). */
    fun setActive(on: Boolean) {
        prefs.active = on
        Toast.makeText(this, if (on) "Pad Overlay on" else "Pad Overlay off", Toast.LENGTH_SHORT).show()
        syncHelper()
        // This is called from the bubble's own touch event, and refresh() replaces the bubble.
        main.post { refresh() }
    }

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        prefs = Prefs(this)
        injector = Injector(this)
        gamepad = Gamepad(this, injector)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        // The service can (re)start while Remote Play is already open, with no window event to tell us.
        inTargetApp = rootInActiveWindow?.packageName?.toString() in prefs.packageSet()
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(deviceListener, null)
        syncHelper()
        refresh()
    }

    override fun onDestroy() {
        instance = null
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(deviceListener)
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
        // While mapping, a pad press also comes back through the helper's virtual controller.
        // Only the real pad's press counts, or the echo would be read as a second press.
        if (editor != null && helperRunning && event.device?.vendorId == 0x054c) return true
        if (!overlayNeeded && editor == null && gamepad.learn == null) {
            return prefs.active && shouldShow() && gamepad.onTouchButtonKey(event)
        }
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
        // Found by name when pressed, so they're always usable and can always be rebound.
        controls.forEach { if (it.id in SYSTEM_BUTTONS) it.enabled = true }
        if (landscape) {
            // Their ✛ only selects them for binding, so keep it clear of the mapping toolbar.
            controls.firstOrNull { it.id == "create" }?.apply { tx = 0.36f; ty = 0.45f }
            controls.firstOrNull { it.id == "options" }?.apply { tx = 0.64f; ty = 0.45f }
        }
    }

    fun px(c: Control) = c.tx * sw
    fun py(c: Control) = c.ty * sh
    fun controlFor(key: Int) = controls.firstOrNull { it.enabled && it.kind == Kind.BUTTON && it.key == key }

    /** Remote Play's label for this key's button, if it's one of those that may sit in a panel. */
    fun systemButtonFor(key: Int): String? =
        SYSTEM_BUTTONS[controls.firstOrNull { it.key == key && it.id in SYSTEM_BUTTONS }?.id]

    /** Tap a Remote Play button by its label, showing its buttons or opening its panel if needed. */
    fun pressByLabel(label: String, mayOpenPanel: Boolean = true, mayReveal: Boolean = true) {
        val node = findVisible(label)
        val panel = if (node == null && mayOpenPanel) findVisible(PANEL_BUTTON) else null
        when {
            node != null -> tap(node)
            panel != null -> {
                tap(panel)
                main.postDelayed({ pressByLabel(label, false, false) }, PANEL_OPEN_MS)
            }
            mayReveal -> revealThen { pressByLabel(label, mayOpenPanel, false) }
        }
    }

    private fun tap(n: AccessibilityNodeInfo) {
        val r = Rect().also { n.getBoundsInScreen(it) }
        injector.up(injector.down(r.exactCenterX(), r.exactCenterY()))
    }

    /**
     * With a controller connected, Remote Play hides its on-screen buttons until the screen is
     * touched. A plain tap on an empty part of the stick area brings them back.
     */
    private fun revealThen(next: () -> Unit) {
        val (fx, fy) = if (landscape) 0.47f to 0.90f else 0.50f to 0.75f
        injector.up(injector.down(fx * sw, fy * sh))
        main.postDelayed(next, REVEAL_MS)
    }

    private fun findVisible(label: String): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() in prefs.packageSet() }
        for (root in roots) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.contentDescription?.toString() == label && it.isVisibleToUser }
                ?.let { return it }
        }
        return null
    }

    /** A real DualSense (or the virtual one) is connected, so Remote Play needs no help from us. */
    private fun nativeControllerPresent() = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.vendorId == 0x054c && it.productId in PS_CONTROLLER_IDS } == true
    }
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
        nativeController = nativeControllerPresent()
        if (!shouldShow()) return
        measure()
        // With a real or virtual PS controller, only the bubble stays: our focus-holding window
        // would keep the controller's input away from Remote Play.
        if (prefs.active && overlayNeeded) {
            addCatcher()
            if (prefs.touchButtons && !touchHidden) addTouchButtons()
        }
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
        val v = HandleView(this, prefs.active, onTap = { setActive(!prefs.active) }, onLongPress = { openEditor() }, onMoved = { rx, ry ->
            lp.x = (rx - size / 2).coerceIn(0, sw - size)
            lp.y = (ry - size / 2).coerceIn(0, sh - size)
            prefs.handleX = lp.x / sw.toFloat(); prefs.handleY = lp.y / sh.toFloat()
            handle?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
        })
        v.alpha = if (prefs.active) 0.8f else 0.55f
        val x = (prefs.handleX * sw).toInt().coerceIn(0, sw - size)
        val y = (prefs.handleY * sh).toInt().coerceIn(0, sh - size)
        lp = params(size, size, x, y)
        wm.addView(v, lp)
        handle = v
    }

    /** Called on any controller press: hide the overlay and start playing. */
    fun onControllerUsed() {
        if (editor != null && gamepad.learn == null) {
            // In PS controller mode the pad is already playing, so only Save or Cancel closes this.
            if (overlayNeeded) closeEditor(save = true)
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
        val lp = params(sw, sh, 0, 0)
        // This window sits above the system bars: step out of the way whenever the nav bar is
        // showing so Back/Home/Recents stay usable. (Full size otherwise, to reach edge targets.)
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener { _, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            val w = sw - nav.left - nav.right
            val h = sh - nav.top - nav.bottom
            if (lp.x != nav.left || lp.y != nav.top || lp.width != w || lp.height != h) {
                lp.x = nav.left; lp.y = nav.top; lp.width = w; lp.height = h
                try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                ev.invalidate()
            }
            insets
        }
        wm.addView(root, lp)
        editor = root
        editorView = ev
        addCatcher()
    }

    fun closeEditor(save: Boolean) {
        gamepad.learn = null
        if (save) {
            prefs.saveLayout(landscape, controls)
            // A pad button means the same thing in both orientations; only positions differ.
            val other = prefs.loadLayout(!landscape)
            other.forEach { o -> controls.firstOrNull { it.id == o.id }?.let { o.key = it.key } }
            prefs.saveLayout(!landscape, other)
        }
        removeEditor()
        touchHidden = false
        syncHelper()
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
                c == null && overlayNeeded -> "Drag each ✛ onto Remote Play's button. Tap one to bind it. Press any controller button to play."
                c == null -> "Tap a ✛ to choose a button, then tap Bind. Tap Save ✓ when done."
                c.kind == Kind.STICK -> "${c.label}: drag ✛ to the stick centre. The dashed ring is how far it pushes."
                else -> "${c.label} → ${keyName(c.key)}. Tap Bind, then press a controller button."
            }
        }
        ev.onSelect = { describe(); ev.invalidate() }

        fun selected(action: (Control) -> Unit) {
            val c = ev.selected
            if (c == null) info.text = "First tap a ✛ to choose which button to change."
            else { action(c); ev.invalidate(); describe() }
        }
        /** describe() is about to overwrite anything else, so this runs after it. */
        fun confirm(text: String) { info.text = text }

        btn("Bind") {
            val c = ev.selected
            if (c?.kind == Kind.STICK) info.text = "${c.label} follows the pad's stick, so it has no button to bind."
            else selected {
                ev.learning = it
                gamepad.learn = { code ->
                    controls.forEach { o -> if (o !== it && o.key == code) o.key = 0 } // one key → one control
                    it.key = code
                    ev.learning = null
                    ev.invalidate()
                    confirm("✓ ${it.label} is now ${keyName(code)}. Tap Save ✓ to keep it.")
                }
            }
        }
        btn("Unbind") {
            selected { it.key = 0 }
            ev.selected?.let { confirm("✓ ${it.label} has no button now. Tap Save ✓ to keep it.") }
        }
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
