package org.starbridge.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.net.Beacon
import org.starbridge.core.net.BrainInfo
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.server.SessionHub
import org.starbridge.core.share.LanAddress
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Foreground service = the telescope "brain". Keeps running with the screen off:
 * USB link to the hand control, web server for the iPhone, gyroscope for backlash.
 */
class StarBridgeService : Service() {

    /** How one item of the status screen is doing. */
    enum class Level { OK, BUSY, WARN, OFF }

    /** A text from the resources, resolved in the chosen language where it is shown. */
    data class Txt(val id: Int, val args: List<Any> = emptyList()) {
        constructor(id: Int, vararg a: Any) : this(id, a.toList())
        fun get(c: Context): String = c.getString(id, *args.toTypedArray())
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    data class Status(
        val telescope: Level = Level.OFF,
        val telescopeText: Txt = Txt(R.string.st_no_cable),
        val gps: Level = Level.WARN,
        val gpsText: Txt = Txt(R.string.st_no_permission),
        val camera: Level = Level.OFF,
        val cameraText: Txt = Txt(R.string.st_no_permission),
        /** Something the user has to know or do (null = all fine). */
        val notice: Txt? = null,
        /** LAN base URL, e.g. http://172.20.10.3:8080; null without WiFi. */
        val url: String? = null,
        val gyro: Boolean = false,
        /** Access key: the QR link is "$url/?k=$key". */
        val key: String? = null,
        /** Another brain on the WiFi has the telescope (the PC): its name and address. */
        val remoteName: String? = null,
        val remoteUrl: String? = null,
    ) {
        val shareUrl: String? get() = if (url != null && key != null) "$url/?k=$key" else null

        /** "172.20.10.3:8080" for typing in Safari. */
        val address: String? get() = url?.removePrefix("http://")

        /** "j576-xmkf-mvu2" for typing on the access screen. */
        val displayKey: String? get() = key?.chunked(4)?.joinToString("-")
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> update { it.copy(notice = Txt(R.string.notice_internal, e.message.orEmpty())) } },
    )
    private lateinit var hub: SessionHub
    private lateinit var web: WebServer
    private lateinit var gps: GpsSource
    private lateinit var settings: PrefsSettings
    private var gyro: GyroMotionSensor? = null
    private var beacon: Beacon? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var camera: Camera2Source? = null
    private var orientation: TubeOrientationSensor? = null

    // Touched from the main thread (receiver, onStartCommand) and the connect coroutine.
    @Volatile private var driver: NexStarDriver? = null
    @Volatile private var device: UsbDevice? = null
    @Volatile private var connecting = false
    private var retryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** After onDestroy nothing may post the (ongoing) notification again. */
    @Volatile private var destroyed = false

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connectUsb()
                    } else {
                        update {
                            it.copy(
                                telescope = Level.WARN, telescopeText = Txt(R.string.st_no_permission),
                                notice = Txt(R.string.notice_usb_denied),
                            )
                        }
                    }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectUsb()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val gone = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    // Ignore other USB devices (e.g. a future camera on the same hub).
                    if (gone == null || gone.deviceName == device?.deviceName) disconnectUsb("cable desconectado")
                }
            }
        }
    }

    /** The QR must follow the WiFi: joining the iPhone hotspot later, or switching networks. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            hub.note("WiFi conectada")
            refreshUrl()
        }
        override fun onLost(network: Network) {
            hub.note("WiFi perdida")
            refreshUrl()
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshUrl()
    }

    /** Screen on/off in the diagnostics: some phones cut WiFi or USB when it goes off. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            hub.note(if (intent.action == Intent.ACTION_SCREEN_OFF) "Pantalla del Android apagada" else "Pantalla del Android encendida")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        settings = PrefsSettings(this)
        gps = GpsSource(this) { site, accuracy, precise ->
            scope.launch { hub.updateGps(site, accuracy, precise) }
            update {
                it.copy(
                    gps = if (precise) Level.OK else Level.BUSY,
                    gpsText = if (precise) accuracy?.let { a -> Txt(R.string.st_gps_accuracy, a.toInt()) } ?: Txt(R.string.st_gps_signal) else Txt(R.string.st_gps_rough),
                )
            }
        }
        hub = SessionHub(scope, Catalog.loadDefault(), clock = { gps.now() }, settings = settings)
        web = WebServer(assets, hub, baseUrl = { status.value.url })
        startBeacon()
        runCatching { web.start() }.onFailure {
            update { s -> s.copy(notice = Txt(R.string.notice_web, it.message.orEmpty())) }
        }

        gyro = GyroMotionSensor(getSystemService(Context.SENSOR_SERVICE) as SensorManager).takeIf { it.available }
        gyro?.start()
        hub.motionSensor = gyro

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        orientation = TubeOrientationSensor(
            sensorManager,
            mounting = { settings.get(SessionHub.KEY_MOUNTING) ?: "top" },
            site = { hub.brain.site },
            now = { gps.now() },
        ).takeIf { it.available }
        orientation?.start()
        hub.orientationSensor = orientation
        startGps()
        ensureImaging()

        acquireLocks()
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback,
            )
        }
        // A hotspot hosted by this phone raises no network callback: look now and then.
        scope.launch {
            while (isActive) {
                delay(URL_REFRESH_MS)
                refreshUrl()
            }
        }
        update { it.copy(url = localUrl(this), gyro = gyro != null, key = hub.accessKey) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ROTATE_KEY) {
            scope.launch {
                val key = hub.rotateKey() // disconnects every device
                update { it.copy(key = key) }
            }
        }
        refreshUrl()
        startGps() // the permission may have been granted since onCreate
        ensureImaging() // same for the camera
        connectUsb()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        // Safety first, and synchronously: the mount must be stopped (and GoTo, tracking and
        // measurements halted so they cannot restart it) before anything goes away.
        val d = driver
        if (d != null) runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { hub.haltAll() } } }
        // Frees port 8080 at once, in case the service is started again right away.
        runCatching { web.stop() }
        beacon?.stop()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        retryJob?.cancel()
        gyro?.stop()
        orientation?.stop()
        gps.stop()
        driver = null
        device = null
        status.value = Status()
        // The rest may take seconds (a camera exposure, saving a stack): never on the main thread.
        val im = hub.imaging
        val cam = camera
        val wake = wakeLock
        val wifi = wifiLock
        CoroutineScope(Dispatchers.Default).launch {
            // The mount first (a halted GoTo may still be sending its stops), then the camera.
            runCatching { hub.detachMount("servicio detenido") }
            d?.disconnect()
            im?.let { withTimeoutOrNull(IMAGING_SHUTDOWN_MS) { it.shutdown() } }
            cam?.release()
            scope.cancel()
            wake?.takeIf { it.isHeld }?.release()
            wifi?.takeIf { it.isHeld }?.release()
        }
        super.onDestroy()
    }

    /**
     * Announce this brain on the WiFi and find the others: if the telescope is on the PC, the
     * pages and this screen offer to use it. Receiving broadcasts needs a multicast lock.
     */
    private fun startBeacon() {
        hub.brainKind = "android"
        val id = settings.get(KEY_BRAIN_ID) ?: Beacon.newId().also { settings.put(KEY_BRAIN_ID, it) }
        multicastLock = runCatching {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("StarBridge::beacon").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        beacon = Beacon(
            scope,
            self = { BrainInfo(id, "android", "Android · ${Build.MODEL}".take(40), WebServer.PORT, hub.hasTelescope) },
            onPeers = { list ->
                hub.setPeers(list)
                val remote = if (hub.hasTelescope) null else list.firstOrNull { it.info.telescope }
                update { it.copy(remoteName = remote?.info?.name, remoteUrl = remote?.baseUrl) }
            },
        ).also { it.start() }
    }

    /** Camera + live stacking, once the camera permission is granted. */
    private fun ensureImaging() {
        if (hub.imaging != null) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        startInForeground() // add the camera type: captures go on with the screen off
        val cam = Camera2Source(this).also { camera = it }
        val session = org.starbridge.core.imaging.ImagingSession(
            scope, cam, AndroidImagingHost(this), settings, java.io.File(filesDir, "imaging"), hub.broadcaster,
        )
        session.gyro = gyro
        hub.imaging = session
        val n = cam.cameras().size
        update {
            if (n == 0) it.copy(camera = Level.OFF, cameraText = Txt(R.string.st_cam_none))
            else it.copy(camera = Level.OK, cameraText = if (n == 1) Txt(R.string.st_cam_ready) else Txt(R.string.st_cam_count, n))
        }
        scope.launch { hub.broadcaster(session.state()) }
    }

    private fun startGps() {
        if (gps.running) return
        if (!gps.hasPermission()) {
            update { it.copy(gps = Level.WARN, gpsText = Txt(R.string.st_no_permission)) }
            return
        }
        // Re-declare the foreground type so location keeps working with the screen off.
        startInForeground()
        gps.start()
        if (!gps.hasFix) update { it.copy(gps = Level.BUSY, gpsText = Txt(R.string.st_gps_searching)) }
    }

    private fun refreshUrl() {
        val url = localUrl(this)
        if (url != status.value.url) update { it.copy(url = url) }
    }

    // --- USB -----------------------------------------------------------------

    private fun connectUsb() {
        if (driver != null || connecting) return
        val serial = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (serial == null) {
            update { it.copy(telescope = Level.OFF, telescopeText = Txt(R.string.st_no_cable), notice = null) }
            return
        }
        val dev = serial.device
        if (!usbManager.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(dev, pi)
            update { it.copy(telescope = Level.BUSY, telescopeText = Txt(R.string.st_usb_permission), notice = Txt(R.string.notice_usb_accept)) }
            return
        }
        connecting = true
        retryJob?.cancel()
        update { it.copy(telescope = Level.BUSY, telescopeText = Txt(R.string.st_connecting), notice = null) }
        scope.launch {
            val port = serial.ports.first()
            var portOpen = false
            var created: NexStarDriver? = null
            try {
                val connection = usbManager.openDevice(dev) ?: error("No se pudo abrir el dispositivo USB")
                port.open(connection)
                portOpen = true
                port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                val d = NexStarDriver(UsbSerialTransport(port), scope).also { created = it }
                val info = d.connect()
                // The cable may have been pulled while we were talking to the hand control.
                if (usbManager.deviceList.values.none { it.deviceName == dev.deviceName }) {
                    error("cable desconectado")
                }
                driver = d
                device = dev
                created = null // owned by the service now
                portOpen = false
                hub.attachMount(d, info)
                update { it.copy(telescope = Level.OK, telescopeText = Txt(R.string.st_connected), notice = null) }
            } catch (e: Exception) {
                created?.disconnect() // also closes the port
                if (portOpen) runCatching { port.close() }
                update {
                    it.copy(
                        telescope = Level.WARN, telescopeText = Txt(R.string.st_no_answer),
                        notice = Txt(R.string.notice_no_answer, e.message.orEmpty()),
                    )
                }
                scheduleRetry()
            } finally {
                connecting = false
            }
        }
    }

    /** Telescope switched off at plug-in time: keep trying while the cable is connected. */
    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(5_000)
            launch(Dispatchers.Main) { connectUsb() }
        }
    }

    private fun disconnectUsb(reason: String) {
        retryJob?.cancel()
        val d = driver ?: return
        driver = null
        device = null
        scope.launch { hub.detachMount(reason) }
        d.disconnect()
        update { it.copy(telescope = Level.OFF, telescopeText = Txt(R.string.st_no_cable), notice = null) }
    }

    // --- Foreground / locks -------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "StarBridge", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Iniciando…")
        // The "location" type is only allowed (and only needed) once the permission is granted.
        val locationGranted =
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraGranted =
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (locationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0) or
            (if (cameraGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        try {
            startForeground(NOTIFICATION_ID, n, type)
        } catch (e: SecurityException) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_starbridge)
            .setColor(getColor(R.color.sb_accent))
            .setContentTitle("StarBridge")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarBridge::service").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StarBridge::wifi").apply { acquire() }
    }

    private fun update(change: (Status) -> Status) {
        if (destroyed) return
        val s = synchronized(status) { change(status.value).also { status.value = it } }
        val text = getString(R.string.notification_line, s.telescopeText.get(this).lowercase(), s.gpsText.get(this).lowercase()) +
            (s.address?.let { " · $it" } ?: "")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL = "starbridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_USB_PERMISSION = "org.starbridge.app.USB_PERMISSION"
        const val ACTION_ROTATE_KEY = "org.starbridge.app.ROTATE_KEY"
        private const val KEY_BRAIN_ID = "brainId"
        private const val URL_REFRESH_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 1_500L
        private const val IMAGING_SHUTDOWN_MS = 3_000L

        private val status = MutableStateFlow(Status())
        val statusFlow: StateFlow<Status> get() = status

        /** Address other phones can reach, e.g. http://172.20.10.3:8080 (WiFi first). */
        fun localUrl(context: Context): String? =
            (wifiAddress(context) ?: interfaceAddress())?.let { "http://$it:${WebServer.PORT}" }

        /** IPv4 of the WiFi network this phone has joined (the iPhone hotspot). */
        private fun wifiAddress(context: Context): String? = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION") // allNetworks: still the simplest way to list them on Android 10
            cm.allNetworks.firstNotNullOfOrNull { n ->
                val wifi = cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!wifi) return@firstNotNullOfOrNull null
                val link = cm.getLinkProperties(n) ?: return@firstNotNullOfOrNull null
                link.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }?.hostAddress
                    ?.takeIf { LanAddress.reachable(link.interfaceName.orEmpty()) }
            }
        }.getOrNull()

        /** Fallback by interface name: a hotspot hosted by this phone is not a joined network. */
        private fun interfaceAddress(): String? = runCatching {
            LanAddress.pick(
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { nif ->
                        nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                            .filter { it.isSiteLocalAddress }
                            .map { nif.name to it.hostAddress.orEmpty() }
                    },
            )
        }.getOrNull()
    }
}
