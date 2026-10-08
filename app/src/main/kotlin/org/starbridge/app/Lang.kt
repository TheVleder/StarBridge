package org.starbridge.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's language (Spanish or English), chosen on the first screen and in the menu. Every
 * activity and the service wrap their context with it; the web pages get it as ?lang=.
 */
object Lang {
    private const val PREFS = "starbridge"
    private const val KEY = "uiLang"
    val SUPPORTED = listOf("es", "en")

    fun saved(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { it in SUPPORTED }

    /** The language in use: the chosen one, else Spanish on Spanish phones and English on the rest. */
    fun current(c: Context): String = saved(c) ?: if (Locale.getDefault().language == "es") "es" else "en"

    fun save(c: Context, lang: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    fun wrap(base: Context): Context {
        val locale = Locale(current(base))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** "Español / English" dialog; [changed] runs only if the language really changed. */
    fun pick(activity: Activity, changed: () -> Unit) {
        val now = current(activity)
        AlertDialog.Builder(activity)
            .setTitle("Idioma · Language")
            .setSingleChoiceItems(arrayOf("Español", "English"), SUPPORTED.indexOf(now)) { dialog, which ->
                dialog.dismiss()
                val lang = SUPPORTED[which]
                save(activity, lang)
                if (lang != now) changed()
            }
            .show()
    }
}
