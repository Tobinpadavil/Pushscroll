package com.teserakt.pushscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kotlin.math.hypot

/**
 * Controls YouTube Shorts while tracking is running:
 *  - swipes to the next Short after every pushup
 *  - puts an invisible shield over Shorts so manual swipes do nothing,
 *    while taps (pause, like, comments, buttons) are passed through
 *  - shows the pushup counter in the corner of YouTube
 * Does nothing at all when tracking is stopped.
 */
class ShortsAccessibilityService : AccessibilityService(), PushupBus.Listener {

    companion object {
        const val YT = "com.google.android.youtube"
        private val SHORTS_IDS = listOf("reel_recycler", "reel_player_page_container", "reel_watch_player")
        @Volatile var instance: ShortsAccessibilityService? = null
    }

    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())

    private var badge: TextView? = null
    private var badgeShown = false
    private var flashUntil = 0L

    private var shield: View? = null
    private var shieldShown = false
    private var shieldParams: WindowManager.LayoutParams? = null

    private var onYt = false
    private var onShorts = false

    private var injecting = false
    private val queue = ArrayDeque<GestureDescription>()

    private var screenW = 1080
    private var screenH = 2400

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 500)
        }
    }
    private val quick = Runnable { refresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        measure()
        PushupBus.add(this)
        h.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            h.removeCallbacks(quick)
            h.postDelayed(quick, 150)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        PushupBus.remove(this)
        h.removeCallbacksAndMessages(null)
        hideBadge()
        hideShield()
        super.onDestroy()
    }

    override fun onPushupEvent(event: Int) {
        if (event == PushupBus.EV_REP) {
            refresh()
            if (onShorts) {
                measure()
                enqueue(swipeUp())
                flash("💪 ${PushupBus.sessionCount}  ▲ next")
            }
        }
        updateBadge()
    }

    // ---------- where are we? ----------

    private fun refresh() {
        if (!PushupBus.running) {
            onYt = false
            onShorts = false
            hideBadge()
            hideShield()
            return
        }
        detect()
        if (onYt) showBadge() else hideBadge()
        if (onShorts && Prefs(this).blockManual) showShield() else hideShield()
        updateBadge()
    }

    private fun detect() {
        var root: AccessibilityNodeInfo? = null
        try {
            val app = windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxByOrNull { it.layer }
            root = app?.root
        } catch (_: Exception) {}
        if (root == null) root = try { rootInActiveWindow } catch (_: Exception) { null }

        onYt = root?.packageName?.toString() == YT
        onShorts = onYt && (Prefs(this).lockAllYoutube || (root != null && isShorts(root)))
    }

    private fun isShorts(root: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        for (id in SHORTS_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId("$YT:id/$id") } catch (_: Exception) { null }
            if (nodes.isNullOrEmpty()) continue
            for (n in nodes) {
                n.getBoundsInScreen(r)
                if (n.isVisibleToUser && r.height() > screenH * 0.5f) return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun measure() {
        try {
            val dm = DisplayMetrics()
            (getSystemService(DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
            if (dm.widthPixels > 0) {
                screenW = dm.widthPixels
                screenH = dm.heightPixels
            }
        } catch (_: Exception) {}
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---------- counter badge ----------

    private fun showBadge() {
        if (badge == null) {
            badge = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                val p = dp(10f).toInt()
                setPadding(p + p / 2, p / 2 + 2, p + p / 2, p / 2 + 2)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20f)
                    setColor(0xCC0B0D10.toInt())
                    setStroke(dp(1.5f).toInt(), 0xFFC6FF3D.toInt())
                }
            }
        }
        if (!badgeShown) {
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(16f).toInt()
                y = dp(110f).toInt()
            }
            try {
                wm.addView(badge, lp)
                badgeShown = true
            } catch (_: Exception) {}
        }
        updateBadge()
    }

    private fun hideBadge() {
        if (badgeShown) {
            try { wm.removeView(badge) } catch (_: Exception) {}
            badgeShown = false
        }
    }

    private fun updateBadge() {
        val b = badge ?: return
        if (SystemClock.uptimeMillis() < flashUntil) return
        b.text = if (onShorts) "💪 ${PushupBus.sessionCount}" else "💪 ${PushupBus.sessionCount} · open Shorts"
    }

    private fun flash(msg: String) {
        badge?.text = msg
        flashUntil = SystemClock.uptimeMillis() + 1300
        h.postDelayed({ updateBadge() }, 1350)
    }

    // ---------- swipe shield ----------

    private fun showShield() {
        if (shield == null) {
            shield = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { v, e -> onShieldTouch(v, e) }
            }
        }
        if (!shieldShown) {
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (injecting) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            shieldParams = lp
            try {
                wm.addView(shield, lp)
                shieldShown = true
                // keep the counter above the shield
                if (badgeShown) {
                    hideBadge()
                    showBadge()
                }
            } catch (_: Exception) {}
        }
    }

    private fun hideShield() {
        if (shieldShown) {
            try { wm.removeView(shield) } catch (_: Exception) {}
            shieldShown = false
        }
    }

    private fun setShieldTouchable(touchable: Boolean) {
        val lp = shieldParams ?: return
        lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (shieldShown) try { wm.updateViewLayout(shield, lp) } catch (_: Exception) {}
    }

    private fun onShieldTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                downT = e.eventTime
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.rawX - downX, e.rawY - downY) > dp(28f)) moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    flash("Do a pushup to scroll 💪")
                } else {
                    val held = e.eventTime - downT
                    // Pass the tap (or long-press) through to YouTube
                    enqueue(tap(downX, downY, if (held < 450) 50L else held.coerceAtMost(1500L)))
                    v.performClick()
                }
            }
        }
        return true
    }

    // ---------- gesture injection ----------

    private fun tap(x: Float, y: Float, duration: Long): GestureDescription {
        val p = Path().apply { moveTo(x, y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, duration))
            .build()
    }

    private fun swipeUp(): GestureDescription {
        val x = screenW * 0.5f
        val p = Path().apply {
            moveTo(x, screenH * 0.72f)
            lineTo(x, screenH * 0.28f)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 220))
            .build()
    }

    private fun enqueue(g: GestureDescription) {
        queue.addLast(g)
        if (!injecting) next()
    }

    private fun next() {
        val g = queue.removeFirstOrNull()
        if (g == null) {
            injecting = false
            setShieldTouchable(true)
            return
        }
        injecting = true
        setShieldTouchable(false) // let our own gesture reach YouTube
        h.postDelayed({
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    h.postDelayed({ next() }, 40)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    h.postDelayed({ next() }, 40)
                }
            }, null)
            if (!ok) h.postDelayed({ next() }, 40)
        }, 60)
    }
}
