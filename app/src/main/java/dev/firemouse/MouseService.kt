package dev.firemouse

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import dev.firemouse.NodeActions.Dir

/**
 * Virtual mouse for Fire TV, adapted from MATVT (github.com/virresh/matvt, GPL-3.0).
 *
 * Remote:   Menu ☰        toggle mouse on/off
 *           D-pad          move cursor (hold to accelerate)
 *           OK             click under the cursor tip
 *           Hold at edge   keep pushing past a screen edge to scroll that way
 *           ⏪ / ⏩         scroll up / down
 *           Back, Home, Play stay normal
 */
class MouseService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private var cursor: CursorView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var mouseOn = false
    private val held = LinkedHashSet<Int>()   // d-pad keys currently held down
    private var heldTicks = 0                 // how long movement has been going (for acceleration)
    private var edgeTicks = 0                 // how long we've been pushing against an edge
    private var okDown = false

    private var screenW = 1920f
    private var screenH = 1080f

    override fun onServiceConnected() {
        Log.i(TAG, "service connected")
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlay()
        // Test hook for driving the mouse from a PC over adb. Only senders holding the DUMP
        // permission (the adb shell, system) can trigger it - normal apps cannot.
        registerReceiver(testHook, IntentFilter(TEST_ACTION), "android.permission.DUMP", null)
    }

    // adb shell am broadcast -a dev.firemouse.TEST --es cmd click|scroll|move|toggle [--ei x N --ei y N] [--es dir DOWN]
    private val testHook = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val c = cursor ?: return
            if (i.hasExtra("x")) c.moveTo(i.getIntExtra("x", 0).toFloat(), i.getIntExtra("y", 0).toFloat())
            when (i.getStringExtra("cmd")) {
                "toggle" -> setMouse(!mouseOn)
                "click" -> clickAtCursor()
                "scroll" -> scrollAtCursor(Dir.valueOf(i.getStringExtra("dir") ?: "DOWN"))
            }
            Log.i(TAG, "test hook: ${i.getStringExtra("cmd")} at (${c.x0.toInt()},${c.y0.toInt()})")
        }
    }

    /**
     * Full-screen transparent overlay that only draws the cursor.
     * TYPE_ACCESSIBILITY_OVERLAY needs no extra permission for an accessibility service,
     * and FLAG_NOT_TOUCHABLE makes it invisible to input so it never blocks the app below.
     */
    private fun addOverlay() {
        val bounds = wm.currentWindowMetrics.bounds
        screenW = bounds.width().toFloat(); screenH = bounds.height().toFloat()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        val v = CursorView(this).apply { visibility = View.GONE; moveTo(screenW / 2, screenH / 2) }
        wm.addView(v, lp)
        cursor = v
        Log.i(TAG, "overlay added, screen ${screenW.toInt()}x${screenH.toInt()}")
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        Log.i(TAG, "key ${e.keyCode} ${KeyEvent.keyCodeToString(e.keyCode)} action=${e.action} repeat=${e.repeatCount} mouse=$mouseOn")
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        if (code == TOGGLE_KEY) {
            if (down && e.repeatCount == 0) setMouse(!mouseOn)
            return true   // swallow both down and up so the app never sees Menu
        }
        if (!mouseOn) return false

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    if (held.add(code) && held.size == 1) startMoving()
                } else {
                    held.remove(code)
                    if (held.isEmpty()) stopMoving()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) okDown = true
                else if (okDown) { okDown = false; clickAtCursor() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) scrollAtCursor(Dir.UP); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) scrollAtCursor(Dir.DOWN); return true }
        }
        return false   // Back, Home, Play/Pause etc. behave normally
    }

    private fun setMouse(on: Boolean) {
        mouseOn = on
        held.clear(); stopMoving(); okDown = false
        cursor?.visibility = if (on) View.VISIBLE else View.GONE
        Toast.makeText(this, if (on) "Mouse on" else "Mouse off", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "mouse ${if (on) "ON" else "OFF"}")
    }

    // ---- movement: a 25 ms loop while any d-pad key is held ----

    private val mover = object : Runnable {
        override fun run() {
            val c = cursor ?: return
            val step = minOf(4f + heldTicks * 1.2f, 36f)   // starts precise, speeds up
            var dx = 0f; var dy = 0f
            if (KeyEvent.KEYCODE_DPAD_LEFT in held) dx -= step
            if (KeyEvent.KEYCODE_DPAD_RIGHT in held) dx += step
            if (KeyEvent.KEYCODE_DPAD_UP in held) dy -= step
            if (KeyEvent.KEYCODE_DPAD_DOWN in held) dy += step

            val maxX = screenW - 1; val maxY = screenH - 1
            val nx = (c.x0 + dx).coerceIn(0f, maxX)
            val ny = (c.y0 + dy).coerceIn(0f, maxY)
            c.moveTo(nx, ny)
            heldTicks++

            // Pushing against an edge? After ~300 ms, scroll that way, then repeat every ~300 ms.
            val edgeDir = when {
                dy > 0 && ny >= maxY -> Dir.DOWN
                dy < 0 && ny <= 0f -> Dir.UP
                dx > 0 && nx >= maxX -> Dir.RIGHT
                dx < 0 && nx <= 0f -> Dir.LEFT
                else -> null
            }
            if (edgeDir != null) {
                if (++edgeTicks >= 12) { edgeTicks = 0; scrollAtCursor(edgeDir) }
            } else edgeTicks = 0

            handler.postDelayed(this, 25)
        }
    }

    private fun startMoving() { heldTicks = 0; edgeTicks = 0; handler.removeCallbacks(mover); handler.post(mover) }
    private fun stopMoving() { handler.removeCallbacks(mover); heldTicks = 0; edgeTicks = 0 }

    // ---- actions ----

    private fun clickAtCursor() {
        val c = cursor ?: return
        c.flash(NodeActions.click(this, c.x0.toInt(), c.y0.toInt()))
    }

    private fun scrollAtCursor(dir: Dir) {
        val c = cursor ?: return
        c.flash(NodeActions.scroll(this, c.x0.toInt(), c.y0.toInt(), dir))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(testHook) }
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "FireMouse"
        const val TOGGLE_KEY = KeyEvent.KEYCODE_MENU
        const val TEST_ACTION = "dev.firemouse.TEST"
    }
}
