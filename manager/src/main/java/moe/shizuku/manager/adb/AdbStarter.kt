package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine

/**
 * Optional knobs for the boot start (see BootFlags); the defaults keep the original behaviour.
 *
 * @param preparedKey an AdbKey built ahead of time.
 * @param setAdbEnabled when false, `adb_enabled` is not written.
 * @param tcpipAfterStart in TCP mode, start the server first and send `tcpip:` afterwards.
 * @param reconnectIntervalMs when > 0, the reconnect after `tcpip:` polls at this interval
 *        (up to [reconnectAttempts]) instead of the growing 0/1/2/3/4 s waits.
 */
class AdbStartOptions(
    val preparedKey: AdbKey? = null,
    val setAdbEnabled: Boolean = true,
    val tcpipAfterStart: Boolean = false,
    val reconnectIntervalMs: Long = 0L,
    val reconnectAttempts: Int = 5,
)

object AdbStarter {
    suspend fun startAdb(
        context: Context,
        port: Int,
        log: ((String) -> Unit)? = null,
        options: AdbStartOptions = AdbStartOptions(),
    ) {
        suspend fun AdbClient.runCommand(cmd: String) {
            command(cmd) { log?.invoke(String(it)) }
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            log?.invoke("Starting with wireless adb...\n")
        
            withContext(Dispatchers.IO) {
                val key = options.preparedKey ?: runCatching {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                }.getOrElse {
                    if (it is CancellationException) throw it
                    else throw AdbKeyException(it)
                }

                val startCommand =
                    if (options.setAdbEnabled) "shell:settings put global adb_enabled 1 ; ${Starter.internalCommand}"
                    else "shell:${Starter.internalCommand}"

                var serverStarted = false
                var activePort = port
                val tcpMode = ShizukuSettings.getTcpMode()
                val tcpPort = ShizukuSettings.getTcpPort()
                if (tcpMode && activePort != tcpPort) {
                    log?.invoke("Connecting on port $activePort...")

                    AdbClient("127.0.0.1", activePort, key).use { client ->
                        client.connect()

                        log?.invoke("Successfully connected on port $activePort...")
                        if (options.tcpipAfterStart) {
                            log?.invoke("starting the server before tcpip")
                            client.runCommand(startCommand)
                            serverStarted = true
                        }
                        log?.invoke("\nRestarting in TCP mode port: $tcpPort")

                        activePort = tcpPort
                        runCatching {
                            client.command("tcpip:$activePort")
                        }.onFailure { if (it !is EOFException && it !is SocketException) throw it } // Expected when ADB restarts in TCP mode
                    }
                }
        
                if (serverStarted) {
                    log?.invoke("Server already started before tcpip, skipping the second connection")
                    return@withContext
                }

                log?.invoke("Connecting on port $activePort...")

                AdbClient("127.0.0.1", activePort, key).use { client ->
                    connectWithRetry(client, options.reconnectIntervalMs, options.reconnectAttempts)
                    log?.invoke("Successfully connected on port $activePort...\n")
                    // adb_enabled is written from the shell UID (it holds WRITE_SECURE_SETTINGS);
                    // ';' keeps the starter running if an OEM refuses the write
                    client.runCommand(startCommand)
                }
            }
        } finally {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        }
    }

    suspend fun stopTcp(context: Context, port: Int) {
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }
        
            val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            if (adbEnabled == 0) throw IllegalStateException("ADB is not enabled")

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            withContext(Dispatchers.IO) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    connectWithRetry(client)
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg = when (it) {
                        is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                        else -> it.message
                    }
                    Toast.makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private suspend fun connectWithRetry(client: AdbClient, intervalMs: Long = 0L, maxAttempts: Int = 5) {
        var delayTime = 0L
        for (attempt in 1..maxAttempts) {
            try {
                delay(delayTime)
                client.connect()
                break
            } catch (e: Exception) {
                if (
                    attempt == maxAttempts ||
                    e is CancellationException ||
                    e is SocketTimeoutException
                ) throw e
                delayTime = if (intervalMs > 0) intervalMs else delayTime + 1000
            }
        }
    }
}