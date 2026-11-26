package com.vibe.assistant.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Vibe's hands on the screen. The user turns this on in Accessibility settings;
 * after that Vibe can read what is on screen, tap, type, scroll and press
 * home/back — only when Gemini asks for it through a tool.
 *
 * It also lets you hold volume-down to talk (push-to-talk), even with the
 * screen locked. A short press still changes the volume as normal.
 */
class VibeAccessibility : AccessibilityService() {

    data class Screen(val pkg: String, val text: String, val nodes: List<AccessibilityNodeInfo>)

    companion object {
        @Volatile var instance: VibeAccessibility? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val want = ComponentName(ctx, VibeAccessibility::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(want, ignoreCase = true) } || instance != null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile var lastEventAt = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** When Vibe itself last tapped / typed / swiped — events right after are its own. */
    @Volatile private var ownActionAt = 0L

    private fun mark() {
        ownActionAt = SystemClock.uptimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.uptimeMillis()
        lastEventAt = now
        // A click Vibe did not make, while Vibe is working the screen = the user's finger.
        // Only clicks count: scrolling and text changes also happen on their own.
        val t = event?.eventType ?: return
        if ((t == AccessibilityEvent.TYPE_VIEW_CLICKED || t == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) &&
            com.vibe.assistant.core.VibeBus.controllingScreen &&
            now - ownActionAt > 1500 &&
            event.packageName?.toString() != packageName
        ) {
            VibeService.instance?.onUserTouchedDuringTask()
        }
    }

    override fun onInterrupt() {}

    // ── Push-to-talk on volume-down ──────────────────────────────────────────

    private var volDownAt = 0L
    private var pttStarted = false
    private val startPtt = Runnable {
        pttStarted = true
        VibeService.send(this, VibeService.A_PTT_DOWN)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!Prefs.pushToTalk || !Prefs.pttVolumeKey || !VibeService.isRunning) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                volDownAt = SystemClock.uptimeMillis()
                pttStarted = false
                main.postDelayed(startPtt, 300)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(startPtt)
                if (pttStarted) {
                    pttStarted = false
                    VibeService.send(this, VibeService.A_PTT_UP)
                } else {
                    // It was a short press: do what volume-down normally does.
                    getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
                    )
                }
            }
        }
        return true
    }

    // ── Reading the screen ───────────────────────────────────────────────────

    /**
     * Every window that is showing, top-most first: pop-up menus, dialogs, bottom
     * sheets, share sheets and system permission prompts each live in a window of
     * their own ABOVE the app. Reading only the active window misses them.
     * Vibe's own windows (bubble, shield) and the keyboard are left out.
     */
    private fun visibleWindows(): List<Pair<android.view.accessibility.AccessibilityWindowInfo?, AccessibilityNodeInfo>> {
        val out = mutableListOf<Pair<android.view.accessibility.AccessibilityWindowInfo?, AccessibilityNodeInfo>>()
        try {
            for (w in windows.orEmpty().sortedByDescending { it.layer }) {
                if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                ) continue
                val wr = w.root ?: continue
                val pkg = wr.packageName?.toString().orEmpty()
                if (pkg == packageName) continue
                // System UI's own bars (status/navigation) are noise; its dialogs are not.
                if (pkg == "com.android.systemui" && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    !w.isFocused && wr.childCount <= 2
                ) continue
                out += w to wr
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) rootInActiveWindow?.let { out += null to it }
        return out
    }

    /** Numbered list of what is visible and actionable, for the model. Pop-ups come first. */
    fun dump(max: Int = 180): Screen {
        val wins = visibleWindows()
        if (wins.isEmpty()) return Screen("", "(স্ক্রিন পড়া যাচ্ছে না)", emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        val r = Rect()

        fun labelOf(n: AccessibilityNodeInfo): String {
            if (n.isPassword) return "••••"
            val parts = listOfNotNull(
                n.text?.toString(), n.contentDescription?.toString(),
                if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
            ).map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.distinct()
            return parts.joinToString(" | ").take(90)
        }

        fun childText(n: AccessibilityNodeInfo, depth: Int): String {
            if (depth > 2) return ""
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val l = labelOf(c)
                if (l.isNotEmpty()) return l
                val d = childText(c, depth + 1)
                if (d.isNotEmpty()) return d
            }
            return ""
        }

        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser || nodes.size >= max) return
            val clickable = n.isClickable || n.isLongClickable
            var label = labelOf(n)
            if (label.isEmpty() && clickable) label = childText(n, 0)
            if (label.isNotEmpty() || clickable || n.isEditable || n.isScrollable) {
                val id = nodes.size
                nodes += n
                n.getBoundsInScreen(r)
                val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
                val flags = buildList {
                    if (clickable) add("tap")
                    if (n.isEditable) add("edit")
                    if (n.isScrollable) add("scroll")
                    if (n.isCheckable) add(if (n.isChecked) "on" else "off")
                    if (n.isSelected) add("selected")
                    if (n.isFocused) add("focused")
                    if (!n.isEnabled) add("disabled")
                }
                sb.append('[').append(id).append("] ").append(cls)
                if (label.isNotEmpty()) sb.append(" \"").append(label).append('"')
                if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        val active = rootInActiveWindow
        val many = wins.size > 1
        for ((i, pair) in wins.withIndex()) {
            val (w, wr) = pair
            if (nodes.size >= max) break
            if (many) {
                val p = wr.packageName?.toString().orEmpty()
                val kind = when {
                    i == 0 && wr != active -> "POP-UP / DIALOG ON TOP — deal with this first"
                    i == 0 -> "TOP WINDOW"
                    else -> "window underneath (covered)"
                }
                val title = if (Build.VERSION.SDK_INT >= 24) w?.title?.toString()?.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty() else ""
                sb.append("--- ").append(kind).append(": ").append(p).append(title).append(" ---\n")
            }
            walk(wr)
        }
        val topPkg = wins.first().second.packageName?.toString()
        return Screen(active?.packageName?.toString() ?: topPkg.orEmpty(), sb.toString(), nodes)
    }

    /** Waits until the screen stops changing (or a time limit passes). */
    suspend fun settle(minMs: Long = 300, maxMs: Long = 2000) {
        val start = SystemClock.uptimeMillis()
        delay(minMs)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEventAt > 350) return
            delay(120)
        }
    }

    // ── Acting ───────────────────────────────────────────────────────────────

    fun tap(n: AccessibilityNodeInfo): Boolean {
        mark()
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        return tapAt(r.exactCenterX(), r.exactCenterY())
    }

    fun longPress(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isLongClickable) x = x.parent
        if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 700)
    }

    fun tapAt(x: Float, y: Float) = gesture(x, y, x, y, 60)

    fun type(target: AccessibilityNodeInfo?, text: String, submit: Boolean): Boolean {
        var n = target
        if (n == null || !n.isEditable) n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (n == null) return false
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        mark()
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submit && Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
    }

    fun scroll(n: AccessibilityNodeInfo?, down: Boolean): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null && !x.isScrollable) x = x.parent
        if (x != null) {
            val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (x.performAction(act)) return true
        }
        return swipe(if (down) "up" else "down")
    }

    /** Finger swipe in the given direction across the middle of the screen. */
    fun swipe(direction: String): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return when (direction) {
            "up" -> gesture(w / 2, h * 0.72f, w / 2, h * 0.28f, 320)
            "down" -> gesture(w / 2, h * 0.28f, w / 2, h * 0.72f, 320)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 280)
            "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 280)
            else -> false
        }
    }

    /**
     * Dispatches a touch and WAITS until Android says it was performed (or
     * cancelled), up to 2 s — so "tapped" means the finger really landed, not just
     * that the request was queued (Lucy's dispatchGestureSync). The result comes
     * back on a thread of its own, so this is safe to call from any thread.
     */
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        mark()
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var done = false
        // Vibe's own finger must get through the shield that blocks the user's.
        shieldPassThrough(true)
        try {
            val sent = try {
                dispatchGesture(g, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done = true
                        latch.countDown()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }
                }, gestureHandler)
            } catch (_: Exception) {
                false
            }
            if (!sent) return false
            latch.await(ms + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            return done
        } finally {
            shieldPassThrough(false)
        }
    }

    // ── The shield: while Vibe works the screen, the user's touches are held back ──

    private var shield: android.widget.FrameLayout? = null
    private var shieldParams: android.view.WindowManager.LayoutParams? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val l = java.util.concurrent.CountDownLatch(1)
        main.post {
            try { block() } finally { l.countDown() }
        }
        l.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * A see-through layer over the whole screen (an accessibility overlay, so it
     * also works on the lock screen and needs no extra permission). Touches on it
     * never reach the app underneath: Vibe keeps working and says, in its own
     * voice, that it is using the phone. A small bar on top has the one control
     * the user keeps — Stop.
     */
    fun showShield() = onMain {
        if (shield != null) return@onMain
        val wm = getSystemService(android.view.WindowManager::class.java)
        val d = resources.displayMetrics.density
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE614132A.toInt())
                cornerRadius = 22 * d
            }
            setPadding((14 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
        }
        bar.addView(android.widget.TextView(this).apply {
            text = "Vibe কাজ করছে — একটু অপেক্ষা করো"
            setTextColor(0xFFF2F2F7.toInt())
            textSize = 13f
        })
        bar.addView(android.widget.TextView(this).apply {
            text = "✋ থামাও"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFF4D6A.toInt())
                cornerRadius = 16 * d
            }
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            isClickable = true
            setOnClickListener { VibeService.send(this@VibeAccessibility, VibeService.A_STOP_TASK) }
        }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (10 * d).toInt() })

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0x01000000) // invisible, but it takes the touches
            addView(bar, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = (36 * d).toInt() })
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    VibeService.instance?.onUserTouchedDuringTask()
                }
                true
            }
        }
        val p = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, p)
            shield = root
            shieldParams = p
        } catch (_: Exception) {}
    }

    fun hideShield() = onMain {
        shield?.let {
            try { getSystemService(android.view.WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        shield = null
        shieldParams = null
    }

    /** Lets a dispatched gesture through the shield for its duration. */
    private fun shieldPassThrough(pass: Boolean) {
        if (shield == null) return
        onMain {
            val v = shield ?: return@onMain
            val p = shieldParams ?: return@onMain
            val f = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            p.flags = if (pass) p.flags or f else p.flags and f.inv()
            try { getSystemService(android.view.WindowManager::class.java).updateViewLayout(v, p) } catch (_: Exception) {}
        }
        if (pass) SystemClock.sleep(60) // let the window manager apply it before the touch arrives
    }

    private val gestureHandler: Handler by lazy {
        val t = android.os.HandlerThread("vibe-gesture").apply { start() }
        Handler(t.looper)
    }

    // ── Waiting for the screen to really change (Lucy's readiness check) ─────

    /** A fingerprint of what is on screen; clock-like text is ignored so a ticking clock isn't "a change". */
    fun signature(): String {
        val s = dump(120)
        return s.pkg + "\n" + s.text.replace(Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b"), "")
    }

    fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    private fun hasFocusedField(): Boolean =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true

    /**
     * After an action, wait until the screen shows its effect instead of sleeping
     * a fixed time: a new screen / content, the keyboard field taking focus after
     * tapping an input, or the right app in front after opening one. Polls every
     * 120 ms; gives up at the deadline (slow apps) and lets the next look decide.
     */
    suspend fun waitForChange(before: String, kind: String, expectPkg: String? = null, intoField: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + when (kind) {
            "open_app" -> 3500L
            "type" -> 1200L
            "tap" -> 1600L
            else -> 1500L
        }
        delay(120)
        while (SystemClock.uptimeMillis() < deadline) {
            if (expectPkg != null && foregroundPackage() == expectPkg) break
            if (intoField && hasFocusedField()) break
            if (expectPkg == null && signature() != before) break
            delay(120)
        }
        // Let the new screen finish drawing.
        settle(150, 900)
    }

    fun global(action: String): Boolean {
        mark()
        val id = when (action) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(id)
    }

    // ── Lock-screen PIN pad (voice unlock) ───────────────────────────────────

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        try { windows?.forEach { w -> w.root?.let { out += it } } } catch (_: Exception) {}
        rootInActiveWindow?.let { out += it }
        return out
    }

    private fun walkAll(n: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walkAll(it, visit) }
    }

    /** The digit keys 0-9 of the PIN pad, if one is on screen. */
    fun pinKeys(): Map<Char, AccessibilityNodeInfo>? {
        val keys = HashMap<Char, AccessibilityNodeInfo>()
        for (root in allRoots()) walkAll(root) { n ->
            if (!n.isVisibleToUser) return@walkAll
            val label = (n.text ?: n.contentDescription)?.toString()?.trim() ?: return@walkAll
            val ch = label.firstOrNull() ?: return@walkAll
            val digit = when (ch) {
                in '0'..'9' -> ch
                in '০'..'৯' -> '0' + (ch - '০')
                else -> return@walkAll
            }
            // "2", "2 ABC", "2, ABC" — but not the time "12:45" or a date.
            if (label.length == 1 || label[1] == ' ' || label[1] == ',' || label[1] == '\n') {
                if (!keys.containsKey(digit) || n.isClickable) keys[digit] = n
            }
        }
        return if (keys.size >= 10) keys else null
    }

    fun pinEnterKey(): AccessibilityNodeInfo? {
        val words = listOf("enter", "ok", "done", "confirm", "ঠিক আছে", "সম্পন্ন")
        for (root in allRoots()) {
            var found: AccessibilityNodeInfo? = null
            walkAll(root) { n ->
                if (found != null || !n.isVisibleToUser) return@walkAll
                val id = n.viewIdResourceName?.lowercase().orEmpty()
                val l = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
                if ("key_enter" in id || "keyenter" in id || l in words) found = n
            }
            if (found != null) return found
        }
        return null
    }

    /** Types the PIN on the lock screen's keypad. True if it was typed. */
    suspend fun typePin(pin: String): Boolean {
        repeat(24) {
            delay(250)
            val keys = pinKeys() ?: return@repeat
            for (c in pin) {
                val k = keys[c] ?: return false
                tap(k)
                delay(110)
            }
            delay(250)
            pinEnterKey()?.let { tap(it) }
            return true
        }
        return false
    }

    /** A small JPEG of the screen, base64 — for screens with no readable text (Android 11+). */
    suspend fun screenshotJpeg(): String? = shot(1280, 80)?.b64

    /** A screenshot: base64 JPEG plus the REAL screen size it was taken at (pixels). */
    data class Shot(val b64: String, val screenW: Int, val screenH: Int)

    /**
     * Screenshot like Mark-LVII's screen_processor: the long side up to [maxSide]
     * (1280 by default), JPEG quality [quality] (82 there). Android allows about one
     * screenshot a second, so a too-soon request waits and tries once more.
     */
    suspend fun shot(maxSide: Int = 1280, quality: Int = 80): Shot? {
        if (Build.VERSION.SDK_INT < 30) return null
        var bmp = grab()
        if (bmp == null) {
            delay(1100)
            bmp = grab()
        }
        bmp ?: return null
        val w = bmp.width
        val h = bmp.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Shot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
    }

    private suspend fun grab(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(soft)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // ── Finding a control by what it is called (Mark-LVII's rule) ───────────

    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("\\s+"), " ").trim()

    /**
     * The on-screen control whose name matches [description], from the
     * accessibility tree — so a tap lands on the real element's exact centre,
     * never on a guessed pixel. Exact name beats "starts with" beats "contains";
     * enabled, tappable controls win ties. Null if nothing is named like that.
     */
    fun findByName(description: String): AccessibilityNodeInfo? {
        val q = fold(description.removeSuffix("button").removeSuffix("বাটন"))
        if (q.length < 2) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        for (root in allRoots()) walkAll(root) { n ->
            if (!n.isVisibleToUser) return@walkAll
            if (n.packageName?.toString() == packageName) return@walkAll // never our own bubble
            val names = listOfNotNull(
                n.text?.toString(), n.contentDescription?.toString(),
                if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
                n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' '),
            ).map { fold(it) }.filter { it.isNotEmpty() }
            var s = 0
            for (name in names) s = maxOf(s, when {
                name == q -> 100
                name.startsWith(q) -> 70
                q.length >= 3 && name.contains(q) -> 50
                name.length >= 3 && q.contains(name) -> 30
                else -> 0
            })
            if (s == 0) return@walkAll
            if (n.isClickable || n.isEditable) s += 10
            if (!n.isEnabled) s -= 40
            if (s > bestScore) {
                bestScore = s
                best = n
            }
        }
        return if (bestScore >= 30) best else null
    }

    /** The smallest tappable control whose rectangle contains (x, y), if any. */
    fun tappableAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val r = Rect()
        for (root in allRoots()) walkAll(root) { n ->
            if (!n.isVisibleToUser || !(n.isClickable || n.isEditable)) return@walkAll
            if (n.packageName?.toString() == packageName) return@walkAll
            n.getBoundsInScreen(r)
            if (!r.contains(x, y)) return@walkAll
            val area = r.width().toLong() * r.height()
            if (area in 1 until bestArea) {
                bestArea = area
                best = n
            }
        }
        return best
    }

    fun longPressAt(x: Float, y: Float) = gesture(x, y, x, y, 700)
}
