package moe.shizuku.manager.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStartOptions
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.BootFlags
import moe.shizuku.manager.utils.BootLog
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/**
 * Direct-boot-aware foreground service that starts the Shizuku server at boot, in both phases:
 * before first unlock (LOCKED_BOOT_COMPLETED) and after it (BOOT_COMPLETED).
 *
 * It does not use WorkManager, because WorkManager/JobScheduler do not dispatch before first
 * unlock. If an attempt fails after unlock, it hands over to the normal AdbStartWorker path
 * (ShizukuReceiverStarter), which has the retry logic and the "Attempt now" notification.
 *
 * Flow:
 *   0. Already running? (only checkable after unlock) -> done.
 *   1. ROOT launch mode -> start via su. Works in either phase where root is available.
 *   2. ADB launch mode:
 *        a. fixed TCP port (TCP mode)   -> loopback connect, no Wi-Fi / mDNS needed.
 *        b. wireless debugging (TLS)    -> wait for Wi-Fi, toggle adb_wifi_enabled 0 -> 1,
 *                                          discover the port via mDNS, retry a few times.
 *   3. Before unlock: once the start command is sent, finish. The binder can only be delivered
 *      after unlock, so the BOOT_COMPLETED run confirms the server instead of waiting here.
 *      After unlock: wait for the binder.
 *
 * Every step is logged via [BootLog] to a device-encrypted file, see BootLog for how to read it.
 *
 * NOTE (Wi-Fi timing, from Dimlos):
 *   AdbDebuggingManager evaluates "adb_wifi_enabled" ONCE, at the moment the setting changes.
 *   If the device is not yet connected to a (trusted) Wi-Fi network at that instant, it logs
 *   "Not connected to any wireless network. Not enabling adbwifi." and never retries.
 *   Early in a locked boot Wi-Fi is usually not up yet, so we (a) wait for Wi-Fi first,
 *   and (b) toggle adb_wifi_enabled 0 -> 1 on every attempt so the framework re-evaluates.
 *
 * NOTE (no double start):
 *   - After unlock, a BOOT_COMPLETED run first checks Shizuku.pingBinder() and skips the start
 *     if the locked-boot run already brought the server up (from Dimlos).
 *   - If a run is still in progress when the next boot broadcast arrives (e.g. still waiting
 *     for Wi-Fi when the user unlocks), the new request is ignored and the running attempt
 *     simply continues.
 */
class DirectBootStartService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var runJob: Job? = null

    // Start of the current run (elapsedRealtime), for the T+ timestamps in the boot log.
    private var runT0 = 0L

    companion object {
        private const val CHANNEL_ID = "BootStart"
        private const val NOTIFICATION_ID = 1449

        // How long to wait for Wi-Fi to come up before the first attempt (locked boot can be slow).
        private const val WIFI_WAIT_MS = 90_000L

        // Extra settle time after Wi-Fi reports available, so the framework's own
        // WifiManager connection info (BSSID) is populated when it checks.
        private const val WIFI_SETTLE_MS = 1_500L

        // Per-attempt mDNS discovery window, and how many toggle+discover attempts we make.
        private const val DISCOVER_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 4

        // staged_discovery: one mDNS discovery for the whole run, with short per-attempt waits
        // (instead of MAX_ATTEMPTS x DISCOVER_TIMEOUT_MS). A failed first toggle costs 4 s, not 15 s.
        // The first value comes from the discover_window_ms flag (default 2 s): a toggle that works
        // publishes the port after ~1 s, so a longer wait only delays the retry of a failed toggle.
        private fun stagedTimeoutsMs(): List<Long> {
            val w = BootFlags.int("discover_window_ms").toLong().coerceAtLeast(500L)
            return listOf(w, w, w + w / 2, w * 3, 10_000L)
        }

        // fast_toggle: gap between writing 0 and 1 when a retry has to re-toggle (the old code
        // always waited 500 ms, even on the first attempt where the value was already 0).
        private const val RETOGGLE_GAP_MS = 150L

        // After unlock, how long to wait for the binder of an already-running server to arrive
        // before concluding that no server is running. (The binder is delivered via the
        // ShizukuProvider, which only works after unlock; in measurements it arrived <1s
        // after unlock and this service starts ~4s after unlock.)
        private const val ALREADY_RUNNING_WAIT_MS = 6_000L

        fun start(context: Context) {
            val i = Intent(context, DirectBootStartService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    private fun log(msg: String, tr: Throwable? = null) = BootLog.log(this, "[service] $msg", tr)

    /** Timeline marker: ms since this run started + ms since boot + adb switches/properties. */
    private fun mark(label: String) =
        log("T+${SystemClock.elapsedRealtime() - runT0}ms $label | ${BootLog.snapshot(this)}")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called for every startForegroundService(), even ignored ones.
        startForeground(NOTIFICATION_ID, buildNotification())

        if (runJob?.isActive == true) {
            log("start requested while a run is still in progress - letting that run continue")
            return START_NOT_STICKY
        }

        runT0 = SystemClock.elapsedRealtime()
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        log("==== new run: keyguardLocked=${km.isKeyguardLocked} userUnlocked=${isUserUnlocked()} sdk=${Build.VERSION.SDK_INT}")
        log("flags: ${BootFlags.reload(this)}")

        runJob = scope.launch {
            try {
                runStart()
            } catch (e: Exception) {
                log("run FAILED", e)
                handOffAfterFailure()
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runStart() {
        mark("run start")

        // STEP 0: if a previous run already started the server, don't kill and restart it
        // (that caused a ~3s outage a few seconds after unlock). Only checkable after unlock.
        if (isUserUnlocked() && isAlreadyRunning()) {
            // If the locked-phase run sent the start command, this is the proof that the server
            // survived everything that happened afterwards (adb_wifi_enabled=0, tcpip, ...).
            mark("server already running (binder alive) - nothing to do")
            return
        }

        when (val mode = ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> startWithRoot()
            LaunchMethod.ADB -> startWithAdb()
            else -> {
                log("no launch mode saved (=$mode) - nothing to do")
                return
            }
        }

        // STEP 3
        if (!isUserUnlocked()) {
            log("start command sent while locked - binder arrives after unlock, the BOOT_COMPLETED run will confirm")
            // pingBinder cannot work while locked, but init.svc.adbd can still be logged.
            verifyAfterStart("locked")
            return
        }
        log("waiting for binder (up to 60s)")
        Starter.waitForBinder { line -> log("binder> ${line.trimEnd()}") }
        mark("server is running")
        verifyAfterStart("unlocked")
    }

    /** STEP 1: root. Same command as ShizukuReceiverStarter.rootStart. */
    private fun startWithRoot() {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            throw IllegalStateException("launch mode is ROOT but no root shell is available")
        }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        log("ROOT: running starter")
        val result = Shell.cmd(Starter.internalCommand).exec()
        result.out.forEach { log("root> $it") }
        result.err.forEach { log("root! $it") }
        log("ROOT: starter exit code ${result.code}")
    }

    /** STEP 2: adb, either via a fixed TCP port or via wireless debugging. */
    private suspend fun startWithAdb() {
        val cr: ContentResolver = contentResolver

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() || tcpPort > 0
        if (!supported) {
            log("ADB: background start not supported on this device (needs Android 11+ or a TCP port)")
            return
        }

        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) {
            log("ADB: WRITE_SECURE_SETTINGS not granted")
            ShizukuReceiverStarter.showPermissionErrorNotification(applicationContext)
            return
        }

        // prewarm_key: build the AdbKey (Keystore decrypt, certificate, SSLContext) while we wait
        // for Wi-Fi, so the TLS handshake is the only work left once the port is known.
        val keyDeferred: Deferred<AdbKey?>? =
            if (BootFlags.bool("prewarm_key") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                scope.async { prewarmKey() }
            else null

        val skipAdbEnabled = BootFlags.bool("skip_adb_enabled")
        if (skipAdbEnabled) {
            log("ADB: EXPERIMENT skip_adb_enabled - not writing adb_enabled")
        } else {
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        }
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        mark("ADB: prepared, tcpPort=$tcpPort tcpMode=${ShizukuSettings.getTcpMode()} wifiRequired=${EnvironmentUtils.isWifiRequired()}")

        try {
            val port: Int = if (!EnvironmentUtils.isWifiRequired()) {
                log("ADB: using fixed TCP port $tcpPort, skipping Wi-Fi and mDNS")
                tcpPort
            } else {
                enableWifiDebuggingAndDiscoverPort(cr)
            }

            val options = AdbStartOptions(
                preparedKey = keyDeferred?.await(),
                setAdbEnabled = !skipAdbEnabled,
                tcpipAfterStart = BootFlags.bool("tcpip_after_start"),
                reconnectIntervalMs = if (BootFlags.bool("fast_reconnect")) 200L else 0L,
                reconnectAttempts = if (BootFlags.bool("fast_reconnect")) 25 else 5,
            )
            mark("ADB: connecting on port $port (preparedKey=${options.preparedKey != null})")
            AdbStarter.startAdb(applicationContext, port, log = { line -> log("adb> ${line.trimEnd()}") }, options = options)
            mark("ADB: start command finished")
        } finally {
            keyDeferred?.cancel()
        }
    }

    /** Builds the key and forces the lazy SSLContext / public key. Returns null on any failure. */
    @SuppressLint("NewApi") // only called when SDK_INT >= R (AdbKey.sslContext needs it)
    private fun prewarmKey(): AdbKey? = try {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        key.sslContext
        key.adbPublicKey
        mark("AdbKey prewarmed")
        key
    } catch (e: Exception) {
        // AdbStarter falls back to building the key itself (and reports AdbKeyException).
        log("AdbKey prewarm failed, will build it later", e)
        null
    }

    /**
     * Survival check, a fixed delay after the start command: is the server still alive, and what
     * is adbd doing? Compares the cases (adb_enabled written or not, tcpip before or after start).
     * pingBinder is only meaningful after unlock; while locked, the BOOT_COMPLETED run logs
     * "server already running" instead, which is the same proof.
     */
    private suspend fun verifyAfterStart(phase: String) {
        val delayMs = BootFlags.int("verify_delay_ms").toLong()
        if (delayMs <= 0) return
        delay(delayMs)
        val unlocked = isUserUnlocked()
        val ping = if (unlocked) Shizuku.pingBinder().toString() else "n/a (locked)"
        mark("VERIFY($phase) +${delayMs}ms after start: pingBinder=$ping adbd='${BootLog.adbdState()}'")
    }

    /**
     * Wait for Wi-Fi, then toggle adb_wifi_enabled 0 -> 1 and look for the TLS port via mDNS.
     * Retries a few times, because the framework only evaluates the setting at the moment it
     * changes.
     *
     * Behaviour is switched by BootFlags: fast_toggle, staged_discovery, wifi_settle_ms,
     * use_tls_port_prop (see BootFlags for what each one does).
     */
    private suspend fun enableWifiDebuggingAndDiscoverPort(cr: ContentResolver): Int {
        val fast = BootFlags.bool("fast_toggle")

        // A leftover 1 would make the later 1-write a no-op for the observer. Reset it now,
        // while we wait for Wi-Fi, instead of paying a fixed gap after Wi-Fi is up.
        if (fast && Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            mark("adb_wifi_enabled was 1, pre-reset to 0")
        }

        val wifiUp = awaitWifi(WIFI_WAIT_MS, waitForIp = fast)
        mark("ADB: Wi-Fi available=$wifiUp (waited up to ${WIFI_WAIT_MS / 1000}s)")
        val settleMs = if (fast) BootFlags.int("wifi_settle_ms").toLong() else WIFI_SETTLE_MS
        if (wifiUp && settleMs > 0) delay(settleMs)

        return if (BootFlags.bool("staged_discovery")) {
            discoverStaged(cr, fast)
        } else {
            discoverLegacy(cr, fast, wifiUp)
        }
    }

    /**
     * Makes the framework see a real 0 -> 1 change of adb_wifi_enabled.
     * Legacy: always write 0, wait 500 ms, write 1.
     * fast: write 0 and wait only if the value is currently 1 (a retry, or a leftover).
     */
    private suspend fun toggleWifiAdb(cr: ContentResolver, fast: Boolean) {
        if (!fast) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(500)
        } else if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
            log("toggle: adb_wifi_enabled is still 1 (framework did not reset it), writing 0 then 1")
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(RETOGGLE_GAP_MS)
        } else {
            log("toggle: adb_wifi_enabled is 0, writing 1")
        }
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
    }

    /** Original strategy: a new mDNS discovery per attempt, started after the toggle, 15 s each. */
    private suspend fun discoverLegacy(cr: ContentResolver, fast: Boolean, wifiUp: Boolean): Int {
        for (attempt in 1..MAX_ATTEMPTS) {
            toggleWifiAdb(cr, fast)
            mark("ADB: adb_wifi_enabled 0->1 (attempt $attempt/$MAX_ATTEMPTS), mDNS discovery (${DISCOVER_TIMEOUT_MS / 1000}s)")
            try {
                return discoverPort(DISCOVER_TIMEOUT_MS)
            } catch (e: TimeoutCancellationException) {
                log("ADB: mDNS discovery timed out on attempt $attempt")
                if (!wifiUp) {
                    // Wi-Fi may have come up while we were waiting; give it another chance.
                    if (awaitWifi(5_000, waitForIp = false)) delay(WIFI_SETTLE_MS)
                }
            }
        }
        throw IllegalStateException("adb wireless port not discovered after $MAX_ATTEMPTS attempts")
    }

    /**
     * staged_discovery: start mDNS discovery once, BEFORE the first toggle, so the record is
     * caught the moment adbd publishes it, and keep it running across attempts. Each attempt waits
     * only STAGED_TIMEOUTS_MS[i] before re-toggling.
     *
     * The service.adb.tls.port property is polled alongside. It is always logged when it first
     * becomes readable; it is only USED if use_tls_port_prop=1 (it may be unreadable for apps).
     */
    private suspend fun discoverStaged(cr: ContentResolver, fast: Boolean): Int {
        val found = Channel<Int>(Channel.CONFLATED)
        val usePortProp = BootFlags.bool("use_tls_port_prop")
        val mdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
            if (p.second > 0) {
                log("mDNS found host=${p.first} port=${p.second}")
                found.trySend(p.second)
            }
        }
        mdns.start()
        mark("ADB: mDNS discovery started before the toggle")
        try {
            val stages = stagedTimeoutsMs()
            for ((i, timeoutMs) in stages.withIndex()) {
                found.tryReceive() // drop a stale port from before this toggle
                toggleWifiAdb(cr, fast)
                mark("ADB: adb_wifi_enabled=1 (attempt ${i + 1}/${stages.size}), waiting ${timeoutMs}ms")
                val port = withTimeoutOrNull(timeoutMs) { awaitPort(found, usePortProp) }
                if (port != null) {
                    mark("ADB: port $port found on attempt ${i + 1}")
                    return port
                }
                log("ADB: no port within ${timeoutMs}ms on attempt ${i + 1}")
            }
        } finally {
            mdns.stop()
        }
        throw IllegalStateException("adb wireless port not discovered after ${stagedTimeoutsMs().size} staged attempts")
    }

    private suspend fun awaitPort(found: Channel<Int>, usePortProp: Boolean): Int {
        var propLogged = false
        while (true) {
            found.tryReceive().getOrNull()?.let { return it }
            val p = BootLog.tlsPortProp()
            if (p > 0) {
                if (!propLogged) {
                    propLogged = true
                    mark("service.adb.tls.port property is readable: $p (used=$usePortProp)")
                }
                if (usePortProp) return p
            }
            delay(50)
        }
    }

    /**
     * Suspends until a Wi-Fi network is available, or [timeoutMs] elapses.
     * Returns true if Wi-Fi is up. Never throws: on any problem it logs and returns false.
     *
     * waitForIp: report "up" only when the Wi-Fi link has a usable address (onLinkPropertiesChanged),
     * instead of at onAvailable. If no address shows up within 3 s of onAvailable, report up anyway.
     */
    private suspend fun awaitWifi(timeoutMs: Long, waitForIp: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Already on Wi-Fi?
            val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                log("awaitWifi: already on Wi-Fi")
                return true
            }

            val result = withTimeoutOrNull(timeoutMs) {
                callbackFlow {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (!waitForIp) {
                                trySend(true)
                            } else {
                                log("awaitWifi: onAvailable, waiting for an IP address")
                                launch {
                                    delay(3_000)
                                    trySend(true)
                                }
                            }
                        }

                        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                            if (waitForIp && lp.linkAddresses.any {
                                    !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress
                                }) {
                                trySend(true)
                            }
                        }
                    }
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    cm.registerNetworkCallback(request, callback)
                    awaitClose { cm.unregisterNetworkCallback(callback) }
                }.first()
            }
            if (result == true) log("awaitWifi: Wi-Fi became available") else log("awaitWifi: timed out")
            result == true
        } catch (e: SecurityException) {
            log("awaitWifi: SecurityException (ACCESS_NETWORK_STATE missing?) - falling back to a fixed wait", e)
            delay(timeoutMs.coerceAtMost(30_000L))
            false
        } catch (e: Exception) {
            log("awaitWifi: unexpected error", e)
            false
        }
    }

    private suspend fun discoverPort(timeoutMs: Long): Int = withTimeout(timeoutMs) {
        callbackFlow {
            val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                if (p.second > 0) {
                    log("mDNS found host=${p.first} port=${p.second}")
                    trySend(p.second)
                }
            }
            adbMdns.start()
            awaitClose { adbMdns.stop() }
        }.first()
    }

    /** Waits briefly for the binder of an already-running server. Only meaningful after unlock. */
    private suspend fun isAlreadyRunning(): Boolean =
        withTimeoutOrNull(ALREADY_RUNNING_WAIT_MS) {
            while (!Shizuku.pingBinder()) delay(250)
            true
        } ?: false

    /**
     * Before unlock: do nothing, the BOOT_COMPLETED run after unlock tries again.
     * After unlock: if the server still isn't up, hand over to the normal AdbStartWorker path,
     * which retries with backoff and shows the "Attempt now" notification.
     */
    private suspend fun handOffAfterFailure() {
        if (!isUserUnlocked()) {
            log("failure while locked - the BOOT_COMPLETED run will retry after unlock")
            return
        }
        if (isAlreadyRunning()) {
            log("server is running despite the error - nothing to hand off")
            ShizukuStateMachine.update()
            return
        }
        log("handing over to ShizukuReceiverStarter (AdbStartWorker retries)")
        ShizukuStateMachine.update()
        ShizukuReceiverStarter.start(applicationContext)
    }

    private fun isUserUnlocked(): Boolean = try {
        (getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
    } catch (e: Exception) {
        false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Start on boot",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle("Starting Shizuku…")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
