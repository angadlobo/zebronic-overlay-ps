package com.padoverlay

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { BUTTON, STICK }

/**
 * One PS control.
 *  - (tx, ty): where Remote Play's own on-screen button is (fraction of screen). We tap there.
 *  - key: the gamepad key code bound to it (0 = none). Sticks use the gamepad's analog axes.
 *  - (x, y), sizeDp: where *your* optional on-screen button sits.
 *  - rangeDp: for sticks, how far from the centre we drag when the stick is fully pushed.
 */
class Control(
    val id: String,
    val label: String,
    val kind: Kind,
    var tx: Float,
    var ty: Float,
    var key: Int,
    var x: Float,
    var y: Float,
    var sizeDp: Float,
    var rangeDp: Float = 0f,
    var enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("kind", kind.name)
        .put("tx", tx.toDouble()).put("ty", ty.toDouble()).put("key", key)
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("size", sizeDp.toDouble()).put("range", rangeDp.toDouble())
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Control(
            o.getString("id"), o.getString("label"), Kind.valueOf(o.getString("kind")),
            o.getDouble("tx").toFloat(), o.getDouble("ty").toFloat(), o.optInt("key", 0),
            o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getDouble("size").toFloat(), o.optDouble("range", 0.0).toFloat(),
            o.optBoolean("enabled", true),
        )
    }
}

fun keyName(code: Int): String =
    if (code == 0) "—" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_")

object Defaults {
    /**
     * Remote Play's landscape layout, measured from its UI on a 2772×1272 phone; fine-tune in the editor.
     * L3, R3, Create, Options and PS start disabled: Remote Play has no always-visible button for
     * them, so tapping a guessed spot would only cause stray touches. "Restore all" enables them.
     */
    fun layout(landscape: Boolean): MutableList<Control> {
        val list = mutableListOf<Control>()
        fun add(id: String, label: String, tx: Float, ty: Float, key: Int,
                size: Float = 54f, kind: Kind = Kind.BUTTON, range: Float = 0f, enabled: Boolean = true) {
            // Your on-screen button starts a bit toward the middle so it doesn't cover the original.
            val x = tx + (0.5f - tx) * 0.3f
            val y = ty + (0.5f - ty) * 0.3f
            list += Control(id, label, kind, tx, ty, key, x, y, size, range, enabled)
        }
        add("cross", "✕", 0.8948f, 0.7531f, KeyEvent.KEYCODE_BUTTON_A)
        add("circle", "○", 0.9596f, 0.6120f, KeyEvent.KEYCODE_BUTTON_B)
        add("square", "□", 0.8301f, 0.6120f, KeyEvent.KEYCODE_BUTTON_X)
        add("triangle", "△", 0.8948f, 0.4709f, KeyEvent.KEYCODE_BUTTON_Y)
        add("up", "▲", 0.1524f, 0.4855f, KeyEvent.KEYCODE_DPAD_UP)
        add("down", "▼", 0.1524f, 0.7370f, KeyEvent.KEYCODE_DPAD_DOWN)
        add("left", "◀", 0.0947f, 0.6112f, KeyEvent.KEYCODE_DPAD_LEFT)
        add("right", "▶", 0.2101f, 0.6112f, KeyEvent.KEYCODE_DPAD_RIGHT)
        add("l1", "L1", 0.2026f, 0.2693f, KeyEvent.KEYCODE_BUTTON_L1)
        add("r1", "R1", 0.8481f, 0.2693f, KeyEvent.KEYCODE_BUTTON_R1)
        add("l2", "L2", 0.1292f, 0.1466f, KeyEvent.KEYCODE_BUTTON_L2)
        add("r2", "R2", 0.9215f, 0.1466f, KeyEvent.KEYCODE_BUTTON_R2)
        add("l3", "L3", 0.33f, 0.92f, KeyEvent.KEYCODE_BUTTON_THUMBL, 44f, enabled = false)
        add("r3", "R3", 0.67f, 0.92f, KeyEvent.KEYCODE_BUTTON_THUMBR, 44f, enabled = false)
        add("create", "Create", 0.28f, 0.08f, KeyEvent.KEYCODE_BUTTON_SELECT, 44f, enabled = false)
        add("options", "Opt", 0.72f, 0.08f, KeyEvent.KEYCODE_BUTTON_START, 44f, enabled = false)
        add("ps", "PS", 0.50f, 0.92f, KeyEvent.KEYCODE_BUTTON_MODE, 44f, enabled = false)
        add("touchpad", "Pad", 0.5254f, 0.1981f, 0, 44f)
        add("ls", "LS", 0.4075f, 0.7972f, 0, 120f, Kind.STICK, 60f)
        add("rs", "RS", 0.6434f, 0.7972f, 0, 120f, Kind.STICK, 60f)
        if (!landscape) applyPortrait(list)
        return list
    }

    /** Remote Play's portrait layout (same phone). Create, PS and Options are always visible here. */
    private fun applyPortrait(list: List<Control>) {
        val at = mapOf(
            "cross" to (0.7524f to 0.7110f), "circle" to (0.8750f to 0.6548f),
            "square" to (0.6297f to 0.6548f), "triangle" to (0.7524f to 0.5985f),
            "up" to (0.2476f to 0.6136f), "down" to (0.2476f to 0.6973f),
            "left" to (0.1564f to 0.6555f), "right" to (0.3388f to 0.6555f),
            "l1" to (0.3227f to 0.5099f), "r1" to (0.6769f to 0.5088f),
            "l2" to (0.1730f to 0.4522f), "r2" to (0.8267f to 0.4511f),
            "create" to (0.2264f to 0.9697f), "ps" to (0.5000f to 0.9697f), "options" to (0.7736f to 0.9697f),
            "touchpad" to (0.5000f to 0.1804f),
            "ls" to (0.3282f to 0.8000f), "rs" to (0.6718f to 0.7992f),
        )
        for (c in list) {
            val (tx, ty) = at[c.id] ?: continue
            c.tx = tx; c.ty = ty; c.enabled = true
            c.x = tx + (0.5f - tx) * 0.3f
            c.y = ty + (0.5f - ty) * 0.3f
            if (c.kind == Kind.STICK) c.rangeDp = 36f
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pad", Context.MODE_PRIVATE)

    /** Master switch. Off: the controller and touch buttons do nothing; only the bubble stays. */
    var active: Boolean
        get() = sp.getBoolean("active", true)
        set(v) = sp.edit().putBoolean("active", v).apply()
    var opacity: Int
        get() = sp.getInt("opacity", 60)
        set(v) = sp.edit().putInt("opacity", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var alwaysShow: Boolean
        get() = sp.getBoolean("alwaysShow", false)
        set(v) = sp.edit().putBoolean("alwaysShow", v).apply()
    /** Show touch buttons on screen (off by default: the physical gamepad is the main input). */
    var touchButtons: Boolean
        get() = sp.getBoolean("touchButtons", false)
        set(v) = sp.edit().putBoolean("touchButtons", v).apply()
    /** Read analog sticks / triggers / hat d-pad. Needs a tiny focus-holding window. */
    var analog: Boolean
        get() = sp.getBoolean("analog", true)
        set(v) = sp.edit().putBoolean("analog", v).apply()
    /** Right stick axes: false = Z/RZ (most Android gamepads), true = RX/RY. */
    var rightStickRx: Boolean
        get() = sp.getBoolean("rightStickRx", false)
        set(v) = sp.edit().putBoolean("rightStickRx", v).apply()
    var swapSticksY: Boolean
        get() = sp.getBoolean("invertY", false)
        set(v) = sp.edit().putBoolean("invertY", v).apply()
    var packages: String
        get() = sp.getString("packages", DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(v) = sp.edit().putString("packages", v).apply()
    var handleX: Float
        get() = sp.getFloat("handleX", 0.5f)
        set(v) = sp.edit().putFloat("handleX", v).apply()
    var handleY: Float
        get() = sp.getFloat("handleY", 0.0f)
        set(v) = sp.edit().putFloat("handleY", v).apply()

    fun packageSet(): Set<String> =
        packages.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun key(landscape: Boolean) = if (landscape) "layout_land" else "layout_port"

    fun loadLayout(landscape: Boolean): MutableList<Control> {
        val s = sp.getString(key(landscape), null) ?: return Defaults.layout(landscape)
        return try {
            val arr = JSONArray(s)
            MutableList(arr.length()) { Control.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Defaults.layout(landscape)
        }
    }

    fun saveLayout(landscape: Boolean, list: List<Control>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(key(landscape), arr.toString()).apply()
    }

    fun resetLayouts() = sp.edit().remove("layout_land").remove("layout_port").apply()

    companion object {
        const val DEFAULT_PACKAGES = "com.playstation.remoteplay, com.scee.psxandroid"
    }
}

/** Sony product IDs Remote Play treats as PS controllers: DualSense, DualShock 4 (v1, v2). */
val PS_CONTROLLER_IDS = setOf(0x0ce6, 0x05c4, 0x09cc)
