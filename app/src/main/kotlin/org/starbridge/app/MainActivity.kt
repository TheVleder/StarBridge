package org.starbridge.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.starbridge.app.StarBridgeService.Level
import org.starbridge.core.share.QrCode

/**
 * Screen of the Android "brain": telescope, GPS and camera at a glance, the QR other phones
 * scan to get the controls (link with the access key), and this phone as a controller.
 * Everything else lives in the menu.
 */
class MainActivity : Activity() {
    private val scope = MainScope()

    private class Chip(root: View, label: String) {
        val dot: View = root.findViewById(R.id.dot)
        val value: TextView = root.findViewById(R.id.value)

        init {
            root.findViewById<TextView>(R.id.label).text = label
        }

        fun show(level: Level, text: String) {
            value.text = text
            dot.backgroundTintList = ColorStateList.valueOf(
                dot.context.getColor(
                    when (level) {
                        Level.OK -> R.color.sb_ok
                        Level.BUSY -> R.color.sb_busy
                        Level.WARN -> R.color.sb_warn
                        Level.OFF -> R.color.sb_off
                    },
                ),
            )
        }
    }

    private lateinit var telescopeChip: Chip
    private lateinit var gpsChip: Chip
    private lateinit var camChip: Chip
    private lateinit var notice: TextView
    private lateinit var qrCard: View
    private lateinit var qr: ImageView
    private lateinit var noWifi: View
    private lateinit var scanTitle: TextView
    private lateinit var access: TextView
    private lateinit var accessHint: TextView
    private lateinit var qrTarget: TextView
    private lateinit var control: Button
    private lateinit var rowBattery: View
    private lateinit var rowLocation: View
    private var shownShareUrl: String? = null
    private var key: String? = null
    private var lastStatus: StarBridgeService.Status? = null

    /** The QR opens the iPhone app StarBridge EAA instead of the browser (remembered). */
    private var appQr = false

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First run: language and what is needed (OTG, permissions, battery…).
        if (!WelcomeActivity.done(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        bindViews()
        requestMissingPermissions()
        startService()
        scope.launch { StarBridgeService.statusFlow.collect(::render) }
    }

    override fun onResume() {
        super.onResume()
        refreshRows() // the user may come back from the system settings
    }

    private fun bindViews() {
        telescopeChip = Chip(findViewById(R.id.chipScope), getString(R.string.chip_telescope))
        gpsChip = Chip(findViewById(R.id.chipGps), getString(R.string.chip_gps))
        camChip = Chip(findViewById(R.id.chipCam), getString(R.string.chip_camera))
        notice = findViewById(R.id.notice)
        qrCard = findViewById(R.id.qrCard)
        qr = findViewById(R.id.qr)
        noWifi = findViewById(R.id.noWifi)
        scanTitle = findViewById(R.id.scanTitle)
        access = findViewById(R.id.access)
        accessHint = findViewById(R.id.accessHint)
        qrTarget = findViewById(R.id.qrTarget)
        appQr = getPreferences(MODE_PRIVATE).getBoolean(PREF_APP_QR, false)
        qrTarget.setOnClickListener {
            appQr = !appQr
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_APP_QR, appQr).apply()
            shownShareUrl = null
            lastStatus?.let(::render)
        }
        control = findViewById(R.id.control)
        control.setOnClickListener {
            val remote = lastStatus?.remoteUrl
            if (remote != null) {
                startActivity(Intent(this, ControlActivity::class.java).putExtra(ControlActivity.EXTRA_URL, "$remote/"))
            } else {
                key?.let { k -> startActivity(Intent(this, ControlActivity::class.java).putExtra(ControlActivity.EXTRA_KEY, k)) }
            }
        }
        findViewById<View>(R.id.wifiSettings).setOnClickListener { openWifiSettings() }
        findViewById<View>(R.id.blackScreen).setOnClickListener { startActivity(Intent(this, BlackScreenActivity::class.java)) }
        findViewById<View>(R.id.menu).setOnClickListener(::showMenu)
        rowBattery = findViewById(R.id.rowBattery)
        setupRow(rowBattery, R.drawable.ic_battery, R.string.battery_title, R.string.battery_text) { askBatteryExemption() }
        rowLocation = findViewById(R.id.rowLocation)
        setupRow(rowLocation, R.drawable.ic_location, R.string.location_title, R.string.location_text) { askLocation() }
    }

    private fun setupRow(row: View, icon: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
        row.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.findViewById<TextView>(R.id.subtitle).setText(subtitle)
        row.setOnClickListener { onClick() }
    }

    private fun render(s: StarBridgeService.Status) {
        lastStatus = s
        telescopeChip.show(s.telescope, s.telescopeText.get(this))
        gpsChip.show(s.gps, s.gpsText.get(this))
        camChip.show(s.camera, s.cameraText.get(this))
        notice.text = s.notice?.get(this)
        notice.visibility = if (s.notice != null) View.VISIBLE else View.GONE

        key = s.key
        // The telescope is on another brain (the PC): this phone becomes its controller.
        val remote = s.remoteUrl != null && s.telescope != StarBridgeService.Level.OK
        control.text = if (remote) getString(R.string.use_remote_controller, s.remoteName ?: "el PC") else getString(R.string.use_as_controller)
        control.isEnabled = remote || s.key != null
        val share = s.shareUrl
        val online = share != null
        qrCard.visibility = if (online) View.VISIBLE else View.GONE
        scanTitle.visibility = if (online) View.VISIBLE else View.GONE
        access.visibility = if (online) View.VISIBLE else View.GONE
        accessHint.visibility = if (online) View.VISIBLE else View.GONE
        qrTarget.visibility = if (online) View.VISIBLE else View.GONE
        noWifi.visibility = if (online) View.GONE else View.VISIBLE
        scanTitle.setText(if (appQr) R.string.scan_title_app else R.string.scan_title)
        accessHint.setText(if (appQr) R.string.access_hint_app else R.string.access_hint)
        qrTarget.setText(if (appQr) R.string.qr_use_browser else R.string.qr_use_app)
        val qrText = share?.let { if (appQr) appLink(it) else it }
        if (qrText != null && qrText != shownShareUrl) {
            qr.setImageBitmap(qrBitmap(qrText))
            access.text = getString(R.string.access_line, s.address, s.displayKey)
        }
        shownShareUrl = qrText
        refreshRows()
    }

    /** Only what still needs doing: battery exemption, location permission. */
    private fun refreshRows() {
        if (!::rowBattery.isInitialized) return
        val pm = getSystemService(PowerManager::class.java)
        rowBattery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        val located = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        rowLocation.visibility = if (located) View.GONE else View.VISIBLE
    }

    /**
     * The same link for the iPhone app StarBridge EAA: its URL scheme makes the iPhone camera open the
     * app directly (http links always open Safari). E.g. starbridge-eaa://172.20.10.3:8080/?k=…
     */
    private fun appLink(share: String) = APP_SCHEME + share.substringAfter("://")

    private fun qrBitmap(text: String): Bitmap {
        val m = QrCode.matrix(text)
        val n = m.size
        val scale = maxOf(1, 720 / n)
        val size = n * scale
        val dark = getColor(R.color.sb_qr_dark)
        val light = getColor(R.color.sb_qr_light)
        val pixels = IntArray(size * size) { i -> if (m[(i / size) / scale][(i % size) / scale]) dark else light }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_rotate_key)
            menu.add(0, 2, 1, R.string.menu_battery)
            menu.add(0, 3, 2, R.string.menu_app_settings)
            menu.add(0, 5, 3, R.string.menu_language)
            menu.add(0, 6, 4, R.string.menu_welcome)
            menu.add(0, 4, 5, R.string.menu_quit)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> confirmRotateKey()
                    2 -> askBatteryExemption(force = true)
                    3 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    4 -> {
                        stopService(Intent(this@MainActivity, StarBridgeService::class.java))
                        finish()
                    }
                    5 -> Lang.pick(this@MainActivity) { restartInLanguage() }
                    6 -> startActivity(Intent(this@MainActivity, WelcomeActivity::class.java).putExtra(WelcomeActivity.EXTRA_REVIEW, true))
                }
                true
            }
        }.show()
    }

    /** The service keeps its texts: restart it so its notification follows the new language. */
    private fun restartInLanguage() {
        stopService(Intent(this, StarBridgeService::class.java))
        startService()
        recreate()
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    /** Asks again; once "don't ask again" is set only the system settings can grant it. */
    private fun askLocation() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
            getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_LOCATION, false).not()
        ) {
            getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_LOCATION, true).apply()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_PERMISSIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            startService() // starts the GPS / camera if now allowed
            refreshRows()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startService() // cable plugged in while the app was open
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startService(action: String? = null) {
        startForegroundService(Intent(this, StarBridgeService::class.java).setAction(action))
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .onFailure { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption(force: Boolean = false) {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } else if (force) {
            // Already exempt; EMUI also has its own "app launch" manager in the battery settings.
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun confirmRotateKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rotate_title)
            .setMessage(R.string.rotate_text)
            .setPositiveButton(R.string.rotate_ok) { _, _ -> startService(StarBridgeService.ACTION_ROTATE_KEY) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val PREF_ASKED_LOCATION = "askedLocation"
        private const val PREF_APP_QR = "qrForApp"
        private const val APP_SCHEME = "starbridge-eaa://"
    }
}
