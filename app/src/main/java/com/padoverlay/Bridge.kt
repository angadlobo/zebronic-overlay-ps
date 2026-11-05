package com.padoverlay

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Presents the real gamepad to Android as a DualSense, so Remote Play treats it as a PS controller.
 *
 * Real pad (/dev/input/eventN) → this process → virtual HID device (/dev/uhid) → hid-playstation driver.
 *
 * Only the adb shell user may open those files, so this runs from adb rather than inside the app:
 * see scripts/pad-bridge.sh. It needs no focus or touch injection, and stops with the process.
 */
object Bridge {
    const val CONTROL_PORT = 27183
    private const val UHID_DESTROY = 1
    private const val UHID_OUTPUT = 6
    private const val UHID_GET_REPORT = 9
    private const val UHID_GET_REPORT_REPLY = 10
    private const val UHID_CREATE2 = 11
    private const val UHID_INPUT2 = 12
    private const val UHID_SET_REPORT = 13
    private const val UHID_SET_REPORT_REPLY = 14
    private const val UHID_EVENT_SIZE = 4380

    private const val SONY = 0x054c
    private const val DUALSENSE = 0x0ce6

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3

    /** Just enough for the driver: it reads raw reports by id and size, not through this descriptor. */
    private val DESCRIPTOR = intArrayOf(
        0x06, 0x00, 0xFF, 0x09, 0x01, 0xA1, 0x01,
        0x85, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x3F, 0x09, 0x01, 0x81, 0x02, // input
        0x85, 0x02, 0x95, 0x2F, 0x09, 0x02, 0x91, 0x02, // output (rumble, lights)
        0x85, 0x05, 0x95, 0x28, 0x09, 0x03, 0xB1, 0x02, // calibration
        0x85, 0x09, 0x95, 0x13, 0x09, 0x04, 0xB1, 0x02, // pairing info
        0x85, 0x20, 0x95, 0x3F, 0x09, 0x05, 0xB1, 0x02, // firmware info
        0xC0,
    ).map { it.toByte() }.toByteArray()

    private lateinit var uhid: FileDescriptor
    private val lock = Any()
    private val running = HashSet<String>()

    // Controller state. Guarded by lock.
    private val axes = intArrayOf(128, 128, 128, 128, 0, 0) // LX, LY, RX, RY, L2, R2
    private var hatX = 0
    private var hatY = 0
    private val pressed = HashSet<Int>()
    private var seq = 0
    private var verbose = false
    private var enabled = true
    private val touchKeys = HashSet<Int>()

    @JvmStatic
    fun main(args: Array<String>) {
        verbose = "--log" in args // print every button and trigger event, to diagnose a pad's layout
        uhid = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        createDevice()
        thread(name = "uhid") { serveDriver() }
        Thread.sleep(500)
        send()
        println("bridge: virtual DualSense created")
        thread(name = "control") { serveControl() }
        while (true) {
            scan()
            Thread.sleep(3000)
        }
    }

    /** Lets the app's on/off bubble add or remove the virtual controller. */
    private fun serveControl() {
        ServerSocket(CONTROL_PORT, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            while (true) {
                try {
                    server.accept().use { s ->
                        s.soTimeout = 500
                        // "<1|0> <comma separated key codes the app presses by touch instead>"
                        val parts = s.getInputStream().bufferedReader().readLine().orEmpty().split(' ')
                        setEnabled(parts[0] != "0")
                        synchronized(lock) {
                            touchKeys.clear()
                            parts.getOrNull(1)?.split(',')?.mapNotNullTo(touchKeys) { it.toIntOrNull() }
                            pressed.clear()
                            axes[4] = 0; axes[5] = 0
                            send()
                        }
                        println("bridge: touch keys $touchKeys")
                        s.getOutputStream().write(if (enabled) '1'.code else '0'.code)
                    }
                } catch (e: Exception) { println("bridge: control: $e") }
            }
        }
    }

    private fun setEnabled(on: Boolean) = synchronized(lock) {
        if (on == enabled) return@synchronized
        if (on) {
            createDevice()
            enabled = true
        } else {
            enabled = false
            val b = le(4).putInt(UHID_DESTROY)
            Os.write(uhid, b.array(), 0, 4)
        }
        println("bridge: controller ${if (on) "on" else "off"}")
    }

    // ---------- virtual device ----------

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun createDevice() {
        val b = le(UHID_EVENT_SIZE)
        b.putInt(UHID_CREATE2)
        b.put("DualSense Wireless Controller".toByteArray()); b.position(4 + 128 + 64 + 64)
        b.putShort(DESCRIPTOR.size.toShort())
        b.putShort(3) // BUS_USB
        b.putInt(SONY); b.putInt(DUALSENSE); b.putInt(0x8000); b.putInt(0)
        b.put(DESCRIPTOR)
        Os.write(uhid, b.array(), 0, UHID_EVENT_SIZE)
    }

    /** The driver asks for calibration, pairing and firmware reports while starting up. */
    private fun serveDriver() {
        val raw = ByteArray(UHID_EVENT_SIZE)
        while (true) {
            val n = Os.read(uhid, raw, 0, raw.size)
            if (n < 4) continue
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            when (b.getInt(0)) {
                UHID_GET_REPORT -> {
                    val id = b.getInt(4)
                    val data = featureReport(raw[8].toInt() and 0xFF)
                    val out = le(12 + (data?.size ?: 0))
                    out.putInt(UHID_GET_REPORT_REPLY); out.putInt(id)
                    out.putShort((if (data == null) OsConstants.EIO else 0).toShort())
                    out.putShort((data?.size ?: 0).toShort())
                    data?.let { out.put(it) }
                    Os.write(uhid, out.array(), 0, out.capacity())
                }
                UHID_SET_REPORT -> {
                    val out = le(10)
                    out.putInt(UHID_SET_REPORT_REPLY); out.putInt(b.getInt(4)); out.putShort(0)
                    Os.write(uhid, out.array(), 0, out.capacity())
                }
                UHID_OUTPUT -> {} // Rumble and lights: nothing to forward them to yet.
            }
        }
    }

    private fun featureReport(id: Int): ByteArray? = when (id) {
        0x05 -> le(41).apply {
            put(5)
            repeat(3) { putShort(0) } // gyro bias
            repeat(3) { putShort(8192); putShort(-8192) } // gyro range
            repeat(2) { putShort(540) } // gyro speed
            repeat(3) { putShort(8192); putShort(-8192) } // accelerometer range
        }.array()
        0x09 -> ByteArray(20).also {
            it[0] = 9
            byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte()).copyInto(it, 1)
        }
        0x20 -> ByteArray(64).also { it[0] = 0x20; it[24] = 0x10; it[44] = 0x30; it[45] = 0x02 }
        else -> null
    }

    /** Must be called with lock held, or before reader threads start. */
    private fun send() {
        if (!enabled) return
        val r = ByteArray(64)
        r[0] = 1
        for (i in axes.indices) r[1 + i] = axes[i].toByte()
        r[7] = (seq++).toByte()
        r[8] = (hat() or
            bit(Button.SQUARE, 4) or bit(Button.CROSS, 5) or bit(Button.CIRCLE, 6) or bit(Button.TRIANGLE, 7)).toByte()
        r[9] = (bit(Button.L1, 0) or bit(Button.R1, 1) or
            (if (axes[4] > 0 || Button.L2 in pressed) 1 shl 2 else 0) or
            (if (axes[5] > 0 || Button.R2 in pressed) 1 shl 3 else 0) or
            bit(Button.CREATE, 4) or bit(Button.OPTIONS, 5) or bit(Button.L3, 6) or bit(Button.R3, 7)).toByte()
        r[10] = bit(Button.PS, 0).toByte()
        r[33] = 0x80.toByte() // no finger on the touchpad
        r[37] = 0x80.toByte()
        r[53] = 0x1A // charging, full: the pad's real battery isn't known
        val out = le(6 + r.size)
        out.putInt(UHID_INPUT2); out.putShort(r.size.toShort()); out.put(r)
        Os.write(uhid, out.array(), 0, out.capacity())
    }

    private fun bit(b: Int, shift: Int) = if (b in pressed) 1 shl shift else 0

    private fun hat(): Int {
        val x = when {
            hatX != 0 -> hatX
            Button.LEFT in pressed -> -1
            Button.RIGHT in pressed -> 1
            else -> 0
        }
        val y = when {
            hatY != 0 -> hatY
            Button.UP in pressed -> -1
            Button.DOWN in pressed -> 1
            else -> 0
        }
        return when (x to y) {
            0 to -1 -> 0; 1 to -1 -> 1; 1 to 0 -> 2; 1 to 1 -> 3
            0 to 1 -> 4; -1 to 1 -> 5; -1 to 0 -> 6; -1 to -1 -> 7
            else -> 8
        }
    }

    // ---------- real pad ----------

    private object Button {
        const val CROSS = 1; const val CIRCLE = 2; const val SQUARE = 3; const val TRIANGLE = 4
        const val L1 = 5; const val R1 = 6; const val L2 = 7; const val R2 = 8
        const val CREATE = 9; const val OPTIONS = 10; const val PS = 11; const val L3 = 12; const val R3 = 13
        const val UP = 14; const val DOWN = 15; const val LEFT = 16; const val RIGHT = 17
    }

    private val KEYS = mapOf(
        304 to Button.CROSS, 305 to Button.CIRCLE, 307 to Button.SQUARE, 308 to Button.TRIANGLE,
        310 to Button.L1, 311 to Button.R1, 312 to Button.L2, 313 to Button.R2,
        314 to Button.CREATE, 315 to Button.OPTIONS, 316 to Button.PS, 317 to Button.L3, 318 to Button.R3,
        544 to Button.UP, 545 to Button.DOWN, 546 to Button.LEFT, 547 to Button.RIGHT,
        // Pads that report as a plain joystick rather than a gamepad.
        288 to Button.CROSS, 289 to Button.CIRCLE, 290 to Button.SQUARE, 291 to Button.TRIANGLE,
        292 to Button.L1, 293 to Button.R1, 294 to Button.L2, 295 to Button.R2,
        296 to Button.CREATE, 297 to Button.OPTIONS, 298 to Button.L3, 299 to Button.R3,
    )

    private class Pad(val path: String, val name: String) {
        val ranges = HashMap<Int, Pair<Int, Int>>()
        var gamepadKeys = false
        val rightOnRx get() = 3 in ranges && 4 in ranges
    }

    private fun scan() {
        val text = try {
            ProcessBuilder("getevent", "-i").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        } catch (e: Exception) { println("bridge: getevent failed: $e"); return }
        for (pad in parsePads(text)) {
            synchronized(running) { if (!running.add(pad.path)) return@synchronized null else Unit } ?: continue
            println("bridge: reading ${pad.name} at ${pad.path} axes=${pad.ranges.keys.sorted()}")
            thread(name = pad.path) {
                try { read(pad) } catch (e: Exception) { println("bridge: ${pad.name} gone: $e") }
                synchronized(running) { running.remove(pad.path) }
                synchronized(lock) {
                    pressed.clear(); hatX = 0; hatY = 0
                    intArrayOf(128, 128, 128, 128, 0, 0).copyInto(axes)
                    send()
                }
            }
        }
    }

    private fun parsePads(text: String): List<Pad> {
        val pads = ArrayList<Pad>()
        for (block in text.split("add device ").drop(1)) {
            val path = Regex("(/dev/input/event\\d+)").find(block)?.value ?: continue
            val name = Regex("name:\\s+\"(.*)\"").find(block)?.groupValues?.get(1) ?: ""
            val vendor = Regex("vendor\\s+([0-9a-f]{4})").find(block)?.groupValues?.get(1)?.toInt(16)
            val product = Regex("product\\s+([0-9a-f]{4})").find(block)?.groupValues?.get(1)?.toInt(16)
            if (vendor == SONY && product == DUALSENSE) continue // that's us
            val pad = Pad(path, name)
            var section = ""
            for (line in block.lines()) {
                Regex("^\\s*([A-Z]+) \\(\\d{4}\\):").find(line)?.let { section = it.groupValues[1] }
                val body = line.substringAfter("):", line).trim()
                if (section == "KEY") {
                    if (!line.contains("input props")) body.split(Regex("\\s+")).forEach { c ->
                        val code = c.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code != null && code in 288..318) pad.gamepadKeys = true
                    }
                } else if (section == "ABS") {
                    Regex("([0-9a-f]{4})\\s+: value -?\\d+, min (-?\\d+), max (-?\\d+)").find(body)?.let {
                        pad.ranges[it.groupValues[1].toInt(16)] = it.groupValues[2].toInt() to it.groupValues[3].toInt()
                    }
                }
            }
            if (pad.gamepadKeys && pad.ranges.isNotEmpty()) pads += pad
        }
        return pads
    }

    private fun read(pad: Pad) {
        val size = 24 // struct input_event on a 64-bit kernel
        val buf = ByteArray(size * 32)
        val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        FileInputStream(pad.path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) return
                synchronized(lock) {
                    for (i in 0 until n / size) {
                        val at = i * size
                        val type = b.getShort(at + 16).toInt() and 0xFFFF
                        val code = b.getShort(at + 18).toInt() and 0xFFFF
                        val value = b.getInt(at + 20)
                        when (type) {
                            EV_KEY -> onKey(code, value)
                            EV_ABS -> onAbs(pad, code, value)
                            EV_SYN -> if (code == 0) send()
                        }
                    }
                }
            }
        }
    }

    private fun onKey(code: Int, value: Int) {
        val b = KEYS[code]
        if (verbose) println("bridge: key $code value $value -> button $b")
        if (code in touchKeys) return
        if (b == null) { println("bridge: unmapped key $code"); return }
        if (value == 0) pressed.remove(b) else pressed.add(b)
    }

    private fun onAbs(pad: Pad, code: Int, value: Int) {
        val (min, max) = pad.ranges[code] ?: return
        if (verbose && code in intArrayOf(9, 10, 16, 17)) println("bridge: axis $code value $value range $min..$max")
        val scaled = if (max > min) ((value - min) * 255L / (max - min)).toInt().coerceIn(0, 255) else 0
        // A trigger the app presses by touch must not also move the virtual trigger.
        if (code == 10 && 312 in touchKeys || code == 9 && 313 in touchKeys) return
        if (pad.rightOnRx && (code == 2 && 312 in touchKeys || code == 5 && 313 in touchKeys)) return
        when (code) {
            0 -> axes[0] = scaled
            1 -> axes[1] = scaled
            2 -> axes[if (pad.rightOnRx) 4 else 2] = scaled // Z: L2 on Xbox-style pads, else right stick X
            5 -> axes[if (pad.rightOnRx) 5 else 3] = scaled // RZ: R2 on Xbox-style pads, else right stick Y
            3 -> axes[2] = scaled
            4 -> axes[3] = scaled
            10 -> axes[4] = scaled // brake
            9 -> axes[5] = scaled // gas
            16 -> hatX = value.coerceIn(-1, 1)
            17 -> hatY = value.coerceIn(-1, 1)
        }
    }
}
