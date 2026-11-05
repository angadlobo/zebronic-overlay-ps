package com.padoverlay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var activeBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            // Take initial focus so the text field below doesn't scroll the page on open.
            isFocusableInTouchMode = true
        }
        fun add(v: View) = col.addView(v)
        fun text(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        }.also(::add)
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }.also(::add)
        fun check(t: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
            text = t; isChecked = value
            setOnCheckedChangeListener { _, b -> onChange(b); PadService.instance?.refresh() }
        }.also(::add)

        text("Pad Overlay", 24f, bold = true)
        text("Use your gamepad (e.g. Zebronics Max Link) with PS Remote Play. Each controller button taps Remote Play's own on-screen button for you.", 14f)
        status = text("", 15f, bold = true)
        activeBox = check("Overlay active (or tap the 🎮 bubble to turn it on/off)", prefs.active) {
            if (it != prefs.active) { prefs.active = it; PadService.instance?.setActive(it) }
        }

        text("Setup", 18f, bold = true)
        button("1. Turn on \"Pad Overlay\" in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        text("Greyed out? Android 13+ blocks this for apps not from the Play Store. Open App info → ⋮ (top right) → \"Allow restricted settings\", then try again.", 13f)
        button("App info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("2. Open Remote Play and map buttons") {
            val svc = PadService.instance
            if (svc == null) { toast("Turn on the accessibility service first (step 1)"); return@button }
            launchTarget()
            handler.postDelayed({ PadService.instance?.openEditor() }, 1200)
        }
        text("In the mapping overlay: drag each amber ✛ onto the matching button in Remote Play. Tap a ✛ → Bind → press the controller button you want. Press any controller button to hide the overlay and play. Hold the 🎮 bubble to bring it back. Tap the bubble to turn everything on or off.", 13f)

        text("Options", 18f, bold = true)
        check("Read sticks, triggers and D-pad hat (recommended)", prefs.analog) { prefs.analog = it }
        check("Right stick uses RX/RY axes (try this if the right stick is wrong)", prefs.rightStickRx) { prefs.rightStickRx = it }
        check("Invert stick up/down", prefs.swapSticksY) { prefs.swapSticksY = it }
        check("Also show on-screen touch buttons", prefs.touchButtons) { prefs.touchButtons = it }
        check("Vibrate on touch-button press", prefs.haptics) { prefs.haptics = it }
        check("Show overlay in every app (not just Remote Play)", prefs.alwaysShow) { prefs.alwaysShow = it }

        text("Touch button opacity", 14f)
        add(SeekBar(this).apply {
            max = 100; progress = prefs.opacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { prefs.opacity = p.coerceAtLeast(10) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { PadService.instance?.refresh() }
            })
        })

        text("Apps to work in (package names, comma separated)", 14f)
        add(EditText(this).apply {
            setText(prefs.packages)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { prefs.packages = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        button("Reset mapping to defaults") {
            AlertDialog.Builder(this)
                .setMessage("Reset all button positions and bindings?")
                .setPositiveButton("Reset") { _, _ -> prefs.resetLayouts(); PadService.instance?.refresh(); toast("Reset") }
                .setNegativeButton("Cancel", null)
                .show()
        }

        setContentView(ScrollView(this).apply {
            addView(col)
            clipToPadding = false
            // Android 15+ draws apps edge to edge: keep content clear of the system bars and keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                }
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val on = PadService.instance != null
        activeBox.isChecked = prefs.active // may have changed from the bubble
        val ps = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { it.vendorId == 0x054c && it.productId == 0x0ce6 } == true
        }
        status.text = when {
            ps -> "🎮 PS controller mode is running: the overlay isn't needed"
            on -> "✅ Service is on"
            else -> "⚠️ Service is off: do step 1"
        }
        status.setTextColor(if (on || ps) Color.rgb(40, 160, 70) else Color.rgb(220, 120, 0))
    }

    private fun launchTarget() {
        val intent = prefs.packageSet().firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) toast("PS Remote Play isn't installed (or add its package name below)")
        else startActivity(intent)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
