package org.starbridge.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First run: the language, then what this phone needs (OTG, permissions, battery) and how
 * the other phones connect. No USB debugging or developer options are needed, and it says so.
 * Can be reopened from the menu ("Ver la bienvenida").
 */
class WelcomeActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(if (Lang.saved(this) == null) languagePage() else checklistPage())
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sizeSp
        setTextColor(getColor(color))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(getColor(if (primary) R.color.sb_accent_ink else R.color.sb_text))
        setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        stateListAnimator = null
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(28), dp(24), dp(28))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(getColor(R.color.sb_bg))
        isFillViewport = true
        addView(content)
    }

    private fun logo() = ImageView(this).apply {
        setImageResource(R.mipmap.ic_launcher)
        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) }
    }

    /** Step 1: Español / English (each in its own language). */
    private fun languagePage(): View {
        val col = column().apply { gravity = Gravity.CENTER }
        col.addView(logo())
        col.addView(text("StarBridge", 30f, R.color.sb_text, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(text("Idioma · Language", 16f, R.color.sb_dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(18)) })
        col.addView(button("Español", true) { choose("es") })
        col.addView(button("English", false) { choose("en") })
        return scroll(col)
    }

    private fun choose(lang: String) {
        Lang.save(this, lang)
        recreate() // now in the chosen language: the checklist
    }

    /** Step 2: what is needed, in order, with buttons for the permissions and the battery. */
    @SuppressLint("BatteryLife")
    private fun checklistPage(): View {
        val col = column()
        col.addView(logo())
        col.addView(text(getString(R.string.welcome_title), 26f, R.color.sb_text, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(text(getString(R.string.welcome_intro), 15f, R.color.sb_dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)) })
        val steps = listOf(
            R.string.welcome_step_otg_title to R.string.welcome_step_otg_text,
            R.string.welcome_step_cable_title to R.string.welcome_step_cable_text,
            R.string.welcome_step_perm_title to R.string.welcome_step_perm_text,
            R.string.welcome_step_battery_title to R.string.welcome_step_battery_text,
            R.string.welcome_step_phone_title to R.string.welcome_step_phone_text,
            R.string.welcome_step_debug_title to R.string.welcome_step_debug_text,
        )
        steps.forEachIndexed { i, (title, body) ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                addView(text("${i + 1}", 18f, R.color.sb_accent, bold = true).apply { minWidth = dp(28) })
                addView(LinearLayout(this@WelcomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(text(getString(title), 16f, R.color.sb_text, bold = true))
                    addView(text(getString(body), 14f, R.color.sb_dim).apply { setPadding(0, dp(4), 0, 0) })
                })
            })
        }
        col.addView(button(getString(R.string.welcome_grant), false) { askPermissions() })
        col.addView(button(getString(R.string.welcome_battery), false) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            }
        })
        col.addView(button(getString(R.string.welcome_start), true) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        })
        col.addView(text(getString(R.string.welcome_change_language), 14f, R.color.sb_dim).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
            setOnClickListener { Lang.pick(this@WelcomeActivity) { recreate() } }
        })
        return scroll(col)
    }

    private fun askPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 7)
    }

    companion object {
        private const val PREFS = "starbridge"
        private const val KEY_DONE = "welcomeDone"
        const val EXTRA_REVIEW = "review"

        fun done(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }
}
