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
    /** Rough guess at Remote Play's landscape layout; you fine-tune it in the editor. */
    fun layout(): MutableList<Control> {
        val list = mutableListOf<Control>()
        fun add(id: String, label: String, tx: Float, ty: Float, key: Int,
                size: Float = 54f, kind: Kind = Kind.BUTTON, range: Float = 0f) {
            // Your on-screen button starts a bit toward the middle so it doesn't cover the original.
            val x = tx + (0.5f - tx) * 0.3f
            val y = ty + (0.5f - ty) * 0.3f
            list += Control(id, label, kind, tx, ty, key, x, y, size, range)
        }
        add("cross", "✕", 0.88f, 0.62f, KeyEvent.KEYCODE_BUTTON_A)
        add("circle", "○", 0.94f, 0.50f, KeyEvent.KEYCODE_BUTTON_B)
        add("square", "□", 0.82f, 0.50f, KeyEvent.KEYCODE_BUTTON_X)
        add("triangle", "△", 0.88f, 0.38f, KeyEvent.KEYCODE_BUTTON_Y)
        add("up", "▲", 0.12f, 0.38f, KeyEvent.KEYCODE_DPAD_UP)
        add("down", "▼", 0.12f, 0.62f, KeyEvent.KEYCODE_DPAD_DOWN)
        add("left", "◀", 0.06f, 0.50f, KeyEvent.KEYCODE_DPAD_LEFT)
        add("right", "▶", 0.18f, 0.50f, KeyEvent.KEYCODE_DPAD_RIGHT)
        add("l1", "L1", 0.07f, 0.24f, KeyEvent.KEYCODE_BUTTON_L1)
        add("r1", "R1", 0.93f, 0.24f, KeyEvent.KEYCODE_BUTTON_R1)
        add("l2", "L2", 0.07f, 0.11f, KeyEvent.KEYCODE_BUTTON_L2)
        add("r2", "R2", 0.93f, 0.11f, KeyEvent.KEYCODE_BUTTON_R2)
        add("l3", "L3", 0.33f, 0.92f, KeyEvent.KEYCODE_BUTTON_THUMBL, 44f)
        add("r3", "R3", 0.67f, 0.92f, KeyEvent.KEYCODE_BUTTON_THUMBR, 44f)
        add("create", "Create", 0.28f, 0.08f, KeyEvent.KEYCODE_BUTTON_SELECT, 44f)
        add("options", "Opt", 0.72f, 0.08f, KeyEvent.KEYCODE_BUTTON_START, 44f)
        add("ps", "PS", 0.50f, 0.92f, KeyEvent.KEYCODE_BUTTON_MODE, 44f)
        add("touchpad", "Pad", 0.50f, 0.08f, 0, 44f)
        add("ls", "LS", 0.24f, 0.78f, 0, 120f, Kind.STICK, 45f)
        add("rs", "RS", 0.76f, 0.78f, 0, 120f, Kind.STICK, 45f)
        return list
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pad", Context.MODE_PRIVATE)

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
        val s = sp.getString(key(landscape), null) ?: return Defaults.layout()
        return try {
            val arr = JSONArray(s)
            MutableList(arr.length()) { Control.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Defaults.layout()
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
