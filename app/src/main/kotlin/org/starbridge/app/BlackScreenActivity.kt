package org.starbridge.app

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * "Pantalla negra": the screen stays on, so the phone never goes to sleep (on some phones,
 * EMUI among them, a switched-off screen ends up cutting the WiFi or pausing the service),
 * but it shows pure black at minimum brightness. On an OLED panel like the P20 Pro's that is
 * no light at all. A double tap or a long press leaves; a single tap only shows how.
 */
class BlackScreenActivity : Activity() {
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val fadeHint = Runnable { hint.animate().alpha(0f).setDuration(HINT_FADE_MS).start() }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = MIN_BRIGHTNESS
            // Black under the notch too.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hint = TextView(this).apply {
            text = getString(R.string.black_hint)
            setTextColor(getColor(R.color.sb_black_hint))
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        hideSystemBars()
        showHint()
    }

    private val taps by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showHint()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }
            override fun onLongPress(e: MotionEvent) = finish()
        })
    }

    // Nothing on screen takes touches: they all come here.
    override fun onTouchEvent(event: MotionEvent): Boolean = taps.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() // they come back after a swipe or a dialog
    }

    override fun onDestroy() {
        handler.removeCallbacks(fadeHint)
        super.onDestroy()
    }

    private fun showHint() {
        hint.animate().cancel()
        hint.alpha = 1f
        handler.removeCallbacks(fadeHint)
        handler.postDelayed(fadeHint, HINT_SHOW_MS)
    }

    /** No status or navigation bar: they would be the only light on the screen. */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    companion object {
        /** Lowest backlight without asking for "off" (0 can mean screen off on some phones). */
        private const val MIN_BRIGHTNESS = 0.01f
        private const val HINT_SHOW_MS = 3_000L
        private const val HINT_FADE_MS = 800L
    }
}
