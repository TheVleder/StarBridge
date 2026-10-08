package org.starbridge.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import android.webkit.WebViewClient

/**
 * "Use this phone as the controller": the same web UI the iPhone gets, shown inside the app
 * and talking to the local server (127.0.0.1) with the access key.
 */
class ControlActivity : Activity() {
    private lateinit var web: WebView

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (webViewTooOld()) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient() // stay inside the app
            // Without a chrome client a WebView silently answers "no" to every confirm():
            // "Nuevo apilado", calibrations… would never run on this phone.
            webChromeClient = WebChromeClient()
            // Exports are already in Downloads/StarBridge on this phone.
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@ControlActivity, R.string.saved_in_downloads, Toast.LENGTH_LONG).show()
            }
        }
        setContentView(web)
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        // Another brain (the PC with the telescope): its page asks for its own code once.
        val remote = intent.getStringExtra(EXTRA_URL)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        // The page follows the language chosen in the app (?lang=, remembered by the page).
        else web.loadUrl((remote ?: "http://127.0.0.1:${WebServer.PORT}/?k=$key").let { u -> u + (if ('?' in u) "&" else "?") + "lang=" + Lang.current(this) })
    }

    /**
     * The web UI needs a 2020+ browser engine (Chrome 80: `?.`, `??`). Phones that never update
     * "Android System WebView" ship an older one and would show a blank page: explain instead.
     */
    private fun webViewTooOld(): Boolean {
        val pkg = WebView.getCurrentWebViewPackage() ?: return false
        val major = pkg.versionName?.substringBefore('.')?.toIntOrNull() ?: return false
        if (major >= MIN_WEBVIEW) return false
        AlertDialog.Builder(this)
            .setTitle(R.string.webview_old_title)
            .setMessage(getString(R.string.webview_old_text, major))
            .setPositiveButton(R.string.webview_update) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${pkg.packageName}"))) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${pkg.packageName}"))) }
                finish()
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        // Closing the page closes its connection: the hub stops anything it was moving.
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_URL = "url"
        private const val MIN_WEBVIEW = 80
    }
}
