package dev.firemouse

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Click and scroll without touch injection.
 *
 * Fire OS cancels every accessibility gesture (dispatchGesture), so instead of faking a finger,
 * we find the UI element under the cursor and ask it to click / scroll itself
 * (AccessibilityNodeInfo.performAction). Works for normal app UI and web pages in browsers.
 */
object NodeActions {
    private const val TAG = "FireMouse"
    private const val MAX_NODES = 6000
    private const val WEB_UNPRUNED_LEVELS = 5

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    fun click(s: AccessibilityService, x: Int, y: Int): Boolean {
        val leaf = deepestAt(s, x, y) ?: return false.also { Log.i(TAG, "click ($x,$y): no node") }
        var n: AccessibilityNodeInfo? = leaf
        while (n != null) {
            if (n.isClickable || n.actionList.contains(AccessibilityAction.ACTION_CLICK)) {
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "click ($x,$y): ${describe(n)} -> $ok")
                if (ok) return true
            }
            n = n.parent
        }
        // Nothing marked clickable. Browsers (Chromium) still accept a click on any page element
        // and fire a real click event on it - this covers JS-only buttons and toggles.
        if (leaf.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "click ($x,$y): direct ${describe(leaf)} -> true")
            return true
        }
        // Text fields etc. can still take focus.
        if (leaf.isFocusable || leaf.isEditable) {
            val ok = leaf.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Log.i(TAG, "click ($x,$y): focus ${describe(leaf)} -> $ok")
            return ok
        }
        Log.i(TAG, "click ($x,$y): nothing clickable under ${describe(leaf)}")
        return false
    }

    fun scroll(s: AccessibilityService, x: Int, y: Int, dir: Dir): Boolean {
        // 1) scrollable containers under the cursor, innermost first
        var n: AccessibilityNodeInfo? = deepestAt(s, x, y, allowFull = false)
        while (n != null) {
            if (n.isScrollable && tryScroll(n, dir)) return true
            n = n.parent
        }
        // 2) otherwise the biggest scrollable thing in that window (e.g. the web page itself)
        val root = windowRootAt(s, x, y) ?: return false
        val all = ArrayList<AccessibilityNodeInfo>()
        collectScrollables(root, all)
        all.sortByDescending { area(it) }
        for (c in all) if (tryScroll(c, dir)) return true
        Log.i(TAG, "scroll $dir ($x,$y): nothing scrollable")
        return false
    }

    private fun tryScroll(n: AccessibilityNodeInfo, dir: Dir): Boolean {
        val directional = when (dir) {
            Dir.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Dir.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Dir.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Dir.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        val actions = n.actionList
        if (actions.contains(directional) && n.performAction(directional.id)) {
            Log.i(TAG, "scroll $dir: ${describe(n)} (directional)"); return true
        }
        // Fallback: forward = down/right, backward = up/left. Only use it if the container's
        // orientation plausibly matches, so a horizontal carousel doesn't eat vertical scrolls.
        val r = Rect().also { n.getBoundsInScreen(it) }
        val vertical = dir == Dir.UP || dir == Dir.DOWN
        if (!actions.contains(directional) && hasOtherAxisOnly(actions, vertical)) return false
        val generic = if (dir == Dir.DOWN || dir == Dir.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                      else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = n.performAction(generic)
        if (ok) Log.i(TAG, "scroll $dir: ${describe(n)} (generic, ${r.width()}x${r.height()})")
        return ok
    }

    // True if the node advertises directional scrolls only for the other axis.
    private fun hasOtherAxisOnly(actions: List<AccessibilityAction>, vertical: Boolean): Boolean {
        val h = actions.contains(AccessibilityAction.ACTION_SCROLL_LEFT) || actions.contains(AccessibilityAction.ACTION_SCROLL_RIGHT)
        val v = actions.contains(AccessibilityAction.ACTION_SCROLL_UP) || actions.contains(AccessibilityAction.ACTION_SCROLL_DOWN)
        return if (vertical) h && !v else v && !h
    }

    /** Root of the topmost window under (x,y), skipping our own cursor overlay. */
    private fun windowRootAt(s: AccessibilityService, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        val candidates = s.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
        for (w in candidates) {
            w.getBoundsInScreen(r)
            if (r.contains(x, y)) w.root?.let { return it }
        }
        return s.rootInActiveWindow
    }

    /**
     * Smallest visible node containing (x,y) in the topmost window there.
     *
     * Native views nest properly, so we skip any branch whose box misses the cursor (fast).
     * Chromium web content does not: a page element's box is often not inside its parent's
     * (layout containers, fixed/absolute positioning, viewport clipping). So inside a WebView we
     * search the top few levels fully, then prune by box below that. If that only finds a big
     * container, we fall back to a full (slow, ~2 s) walk.
     */
    private fun deepestAt(s: AccessibilityService, x: Int, y: Int, allowFull: Boolean = true): AccessibilityNodeInfo? {
        val root = windowRootAt(s, x, y) ?: return null
        val t0 = System.currentTimeMillis()
        val screen = s.resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }
        fun good(n: AccessibilityNodeInfo?) = n != null && (area(n) <= screen / 8 || !isInsideWeb(n))
        // Pass 1: strict pruning. Pass 2: tolerate up to 2 levels of boxes that miss the cursor
        // (CSS floats/overflow). Pass 3 (clicks only): everything.
        var visited = 0
        var mode = "strict"
        var (best, v1) = search(root, x, y, missBudget = 0); visited += v1
        if (!good(best)) {
            val (b2, v2) = search(root, x, y, missBudget = 2); visited += v2; mode = "tolerant"
            if (b2 != null && (best == null || area(b2) < area(best!!))) best = b2
        }
        if (allowFull && !good(best)) {
            val (b3, v3) = search(root, x, y, missBudget = Int.MAX_VALUE); visited += v3; mode = "full"
            if (b3 != null) best = b3
        }
        Log.i(TAG, "search ($x,$y) $mode: $visited nodes in ${System.currentTimeMillis() - t0}ms -> ${best?.let { describe(it) }}")
        return best
    }

    /** missBudget: how many levels in a row we may follow boxes that don't contain the cursor. */
    private fun search(root: AccessibilityNodeInfo, x: Int, y: Int, missBudget: Int): Pair<AccessibilityNodeInfo?, Int> {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        var visited = 0
        val r = Rect()
        // webDepth: -1 outside web content, else levels below the WebView node.
        fun walk(n: AccessibilityNodeInfo?, depth: Int, webDepth: Int, misses: Int) {
            if (n == null || depth > 80 || visited > MAX_NODES) return
            visited++
            n.getBoundsInScreen(r)
            val hit = !r.isEmpty && r.contains(x, y)
            if (hit && n.isVisibleToUser) {
                val a = r.width().toLong() * r.height()
                if (a <= bestArea) { best = n; bestArea = a }
            }
            val wd = if (webDepth >= 0) webDepth + 1 else if (isWebView(n)) 0 else -1
            val m = if (hit) 0 else misses + 1
            val descend = hit || (wd in 0..WEB_UNPRUNED_LEVELS) || (wd >= 0 && m <= missBudget)
            if (descend) for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, wd, m)
        }
        walk(root, 0, -1, 0)
        return best to visited
    }

    private fun isWebView(n: AccessibilityNodeInfo) = n.className?.contains("WebView") == true

    private fun isInsideWeb(n: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = n
        while (p != null) { if (isWebView(p)) return true; p = p.parent }
        return false
    }

    private fun collectScrollables(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 80) return
        if (n.isScrollable && n.isVisibleToUser) out.add(n)
        for (i in 0 until n.childCount) collectScrollables(n.getChild(i), out, depth + 1)
    }

    private fun area(n: AccessibilityNodeInfo): Long {
        val r = Rect(); n.getBoundsInScreen(r); return r.width().toLong() * r.height()
    }

    private fun describe(n: AccessibilityNodeInfo) =
        "${n.className?.toString()?.substringAfterLast('.')}'${(n.text ?: n.contentDescription ?: "").toString().take(30)}'"
}
