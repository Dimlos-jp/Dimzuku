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
import moe.shizuku.manager.adb.AdbPortScanner
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
 * Direct-boot-aware foreground service that starts the Shizuku server at boot, before the first
 * unlock (LOCKED_BOOT_COMPLETED) and after it (BOOT_COMPLETED). It does not use WorkManager,
 * which does not run before first unlock. A failed attempt after unlock is handed over to
 * AdbStartWorker (retry with backoff, "Attempt now" notification).
 *
 * Flow:
 *   0. Server already running (checkable after unlock only) -> nothing to do.
 *   1. Root launch mode -> start through su.
 *   2. ADB launch mode: with a fixed TCP port, connect over loopback; otherwise wait for Wi-Fi,
 *      toggle adb_wifi_enabled 0 -> 1, find the wireless-debugging port, connect and start.
 *   3. Before unlock the run ends once the start command is sent (the binder is only delivered
 *      after unlock); the BOOT_COMPLETED run confirms. After unlock it waits for the binder.
 *
 * AdbDebuggingManager evaluates adb_wifi_enabled once, when the setting changes. Without a
 * connected Wi-Fi at that moment it ignores the change, so the toggle waits for Wi-Fi and
 * is repeated until a port shows up.
 *
 * A run still in progress when the next boot broadcast arrives is not restarted.
 */
class DirectBootStartService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var runJob: Job? = null

    // elapsedRealtime at the start of the run, for the T+ timestamps in the boot log
    private var runT0 = 0L

    companion object {
        private const val CHANNEL_ID = "BootStart"
        private const val NOTIFICATION_ID = 1449

        // How long to wait for Wi-Fi before the first attempt (a locked boot can be slow).
        private const val WIFI_WAIT_MS = 90_000L

        // Settle time after Wi-Fi reports available, used when fast_toggle is off.
        private const val WIFI_SETTLE_MS = 1_500L

        // Discovery window and attempt count of the non-staged path.
        private const val DISCOVER_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 4

        // staged_discovery: short per-attempt waits; the first comes from discover_window_ms.
        private fun stagedTimeoutsMs(): List<Long> {
            val w = BootFlags.int("discover_window_ms").toLong().coerceAtLeast(500L)
            return listOf(w, w, w + w / 2, w * 3, 10_000L)
        }

        // fast_toggle: gap between writing 0 and 1 when a retry re-toggles
        private const val RETOGGLE_GAP_MS = 150L

        // After unlock: how long to wait for the binder of an already-running server
        private const val ALREADY_RUNNING_WAIT_MS = 6_000L

        // From API 37 an app can see 0 for an adb switch that is stored as 1 (read redaction;
        // active for adb_wifi_enabled on the Android 17 QPR3 betas, not yet on QPR1 stable).
        // A read is then no proof of the real value, so the toggle does not branch on it there.
        private val READS_MAY_BE_REDACTED = Build.VERSION.SDK_INT >= 37

        /**
         * Hands the run over to the service. Returns false, after writing the reason to the boot
         * log, when the system refuses the foreground-service start (for example
         * ForegroundServiceStartNotAllowedException on some OEM builds), so that the exception does
         * not take the boot receiver down with it.
         *
         * The guard was inspired by Nightzuku's Direct Boot implementation (kerneldroid/Nightzuku,
         * commit d03716d3), which catches a rejected startForegroundService() the same way. Here
         * the only fallback is the second boot broadcast: the receiver tries again at
         * BOOT_COMPLETED.
         */
        fun start(context: Context): Boolean {
            return try {
                val i = Intent(context, DirectBootStartService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                true
            } catch (e: Exception) {
                BootLog.log(context, "[service] the system refused to start the service: ${e.javaClass.simpleName}", e)
                false
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
        log("device: ${BootLog.deviceInfo(this)}")
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

        // Step 0: don't restart a server that is already running (only checkable after unlock)
        if (isUserUnlocked() && isAlreadyRunning()) {
            mark("server already running (binder alive) - nothing to do")
            return
        }

        when (val mode = ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> startWithRoot()
            LaunchMethod.ADB -> if (!startWithAdb()) {
                log("ADB: nothing was started - not waiting for the binder")
                return
            }
            else -> {
                log("no launch mode saved (=$mode) - nothing to do")
                return
            }
        }

        // Step 3
        if (!isUserUnlocked()) {
            log("start command sent while locked - binder arrives after unlock, the BOOT_COMPLETED run will confirm")
            verifyAfterStart("locked")
            return
        }
        log("waiting for binder (up to 60s)")
        Starter.waitForBinder { line -> log("binder> ${line.trimEnd()}") }
        mark("server is running")
        verifyAfterStart("unlocked")
    }

    /** Step 1: root, same command as ShizukuReceiverStarter.rootStart. */
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

    /**
     * Step 2: adb, via a fixed TCP port or wireless debugging.
     * Returns false when nothing was started (unsupported device or missing WRITE_SECURE_SETTINGS),
     * so the caller does not log a start or wait for a binder that will not come.
     */
    private suspend fun startWithAdb(): Boolean {
        val cr: ContentResolver = contentResolver

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() || tcpPort > 0
        if (!supported) {
            log("ADB: background start not supported on this device (needs Android 11+ or a TCP port)")
            return false
        }

        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED) {
            log("ADB: WRITE_SECURE_SETTINGS not granted")
            ShizukuReceiverStarter.showPermissionErrorNotification(applicationContext)
            return false
        }

        // prewarm_key: build the AdbKey while waiting for Wi-Fi
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
        return true
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

    /** Logs, a fixed delay after the start command, whether the server and adbd are still alive. */
    private suspend fun verifyAfterStart(phase: String) {
        val delayMs = BootFlags.int("verify_delay_ms").toLong()
        if (delayMs <= 0) return
        delay(delayMs)
        val unlocked = isUserUnlocked()
        val ping = if (unlocked) Shizuku.pingBinder().toString() else "n/a (locked)"
        mark("VERIFY($phase) +${delayMs}ms after start: pingBinder=$ping adbd='${BootLog.adbdState()}'")
    }

    /**
     * Waits for Wi-Fi, toggles adb_wifi_enabled 0 -> 1 and returns the wireless-debugging port,
     * found by AdbPortScanner or mDNS, whichever answers first. Behaviour is switched by BootFlags.
     */
    private suspend fun enableWifiDebuggingAndDiscoverPort(cr: ContentResolver): Int {
        val fast = BootFlags.bool("fast_toggle")
        val staged = BootFlags.bool("staged_discovery")

        val found = Channel<Int>(Channel.CONFLATED)
        fun newMdns() = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
            if (p.second > 0) {
                log("mDNS found host=${p.first} port=${p.second}")
                found.trySend(p.second)
            }
        }.also { it.debug = { msg -> log(msg) } }

        val scanner = AdbPortScanner({ log(it) }) { p -> found.trySend(p) }

        // A leftover 1 would turn the later write of 1 into a no-op; reset it while waiting for Wi-Fi.
        // Where reads may be redacted the value cannot be trusted, so 0 is written without reading.
        if (fast) {
            if (READS_MAY_BE_REDACTED) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
                mark("adb_wifi_enabled: reads may be redacted on API 37+, pre-reset to 0 without reading")
            } else if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
                mark("adb_wifi_enabled was 1, pre-reset to 0")
            }
        }

        val wifiUp = awaitWifi(WIFI_WAIT_MS, waitForIp = fast)
        mark("ADB: Wi-Fi available=$wifiUp (waited up to ${WIFI_WAIT_MS / 1000}s)")
        val settleMs = if (fast) BootFlags.int("wifi_settle_ms").toLong() else WIFI_SETTLE_MS
        if (wifiUp && settleMs > 0) delay(settleMs)

        return if (staged) {
            try {
                discoverStaged(cr, fast, found, ::newMdns, scanner)
            } finally {
                scanner.stop()
            }
        } else {
            discoverLegacy(cr, fast, wifiUp)
        }
    }

    /**
     * Makes the framework see a real 0 -> 1 change; with [fast] only if the value is 1 now.
     * On a [retry] where reads may be redacted the read is not trusted: 0 then 1 is always written.
     * (The first attempt relies on the pre-reset instead, and so avoids the extra gap.)
     */
    private suspend fun toggleWifiAdb(cr: ContentResolver, fast: Boolean, retry: Boolean) {
        if (!fast) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(500)
        } else if (retry && READS_MAY_BE_REDACTED) {
            log("toggle: reads may be redacted on API 37+, writing 0 then 1 without reading")
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(RETOGGLE_GAP_MS)
        } else if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
            log("toggle: adb_wifi_enabled is still 1 (framework did not reset it), writing 0 then 1")
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0)
            delay(RETOGGLE_GAP_MS)
        } else {
            log("toggle: adb_wifi_enabled is 0, writing 1")
        }
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
    }

    /** Original strategy: a new mDNS discovery per attempt, 15 s each. */
    private suspend fun discoverLegacy(cr: ContentResolver, fast: Boolean, wifiUp: Boolean): Int {
        for (attempt in 1..MAX_ATTEMPTS) {
            toggleWifiAdb(cr, fast, retry = attempt > 1)
            mark("ADB: adb_wifi_enabled 0->1 (attempt $attempt/$MAX_ATTEMPTS), mDNS discovery (${DISCOVER_TIMEOUT_MS / 1000}s)")
            try {
                return discoverPort(DISCOVER_TIMEOUT_MS)
            } catch (e: TimeoutCancellationException) {
                log("ADB: mDNS discovery timed out on attempt $attempt")
                if (!wifiUp) {
                    if (awaitWifi(5_000, waitForIp = false)) delay(WIFI_SETTLE_MS)
                }
            }
        }
        throw IllegalStateException("adb wireless port not discovered after $MAX_ATTEMPTS attempts")
    }

    /** One mDNS discovery for the whole run, short waits, re-toggle when no port shows up. */
    private suspend fun discoverStaged(
        cr: ContentResolver,
        fast: Boolean,
        found: Channel<Int>,
        newMdns: () -> AdbMdns,
        scanner: AdbPortScanner,
    ): Int {
        val useScan = BootFlags.bool("port_scan")
        val mdns = newMdns().also {
            it.start()
            mark("ADB: mDNS discovery started")
        }
        try {
            val stages = stagedTimeoutsMs()
            for ((i, timeoutMs) in stages.withIndex()) {
                found.tryReceive() // drop a stale port from before this toggle
                toggleWifiAdb(cr, fast, retry = i > 0)
                if (useScan) scanner.start()
                mark("ADB: adb_wifi_enabled=1 (attempt ${i + 1}/${stages.size}), waiting ${timeoutMs}ms")
                val port = withTimeoutOrNull(timeoutMs) { found.receive() }
                scanner.stop()
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

    /**
     * Suspends until Wi-Fi is available or [timeoutMs] elapses; never throws. With [waitForIp],
     * "up" means the link has an address (or 3 s passed since onAvailable).
     */
    private suspend fun awaitWifi(timeoutMs: Long, waitForIp: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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

    /** Before unlock: nothing, the BOOT_COMPLETED run tries again. After unlock: AdbStartWorker. */
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
