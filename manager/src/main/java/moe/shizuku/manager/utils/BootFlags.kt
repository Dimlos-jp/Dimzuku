package moe.shizuku.manager.utils

import android.content.Context
import java.io.File

/**
 * Experiment flags for the boot start, read before first unlock from a text file in
 * device-encrypted storage:
 *
 *     /data/user_de/0/<applicationId>/files/boot_experiment_flags.txt
 *
 * One `key=value` per line, `#` starts a comment; missing keys use the defaults below. The file is
 * re-read at the start of every run and the effective values are logged. Debug builds only:
 *     adb shell run-as <applicationId> sh -c 'echo wifi_settle_ms=300 > /data/user_de/0/<applicationId>/files/boot_experiment_flags.txt'
 *
 * prewarm_key=1         build the AdbKey while waiting for Wi-Fi
 * fast_toggle=1         write 0 before 1 only if the value is currently 1, no fixed gap
 * staged_discovery=1    one mDNS discovery for the whole run, short waits (discover_window_ms)
 * port_scan=1           find the port by scanning loopback (AdbPortScanner), next to mDNS
 * wifi_settle_ms=0      extra wait after Wi-Fi has an address; raise it if the first toggle fails
 * discover_window_ms=1400  wait for a port after a toggle before toggling again
 * fast_reconnect=1      poll the reconnect after `tcpip:` every 200 ms
 * skip_adb_enabled=0    do not write adb_enabled=1
 * tcpip_after_start=0   in TCP mode, start the server before restarting adbd
 * verify_delay_ms=10000 delay of the survival check after the start command
 */
object BootFlags {

    private const val FILE_NAME = "boot_experiment_flags.txt"

    private val DEFAULTS = linkedMapOf(
        "prewarm_key" to "1",
        "fast_toggle" to "1",
        "staged_discovery" to "1",
        "wifi_settle_ms" to "0",
        "discover_window_ms" to "1400",
        "fast_reconnect" to "1",
        "port_scan" to "1",
        "skip_adb_enabled" to "0",
        "tcpip_after_start" to "0",
        "verify_delay_ms" to "10000",
    )

    @Volatile
    private var values: Map<String, String> = DEFAULTS

    /** Re-reads the file. Call at the start of each run. Returns a one-line description. */
    fun reload(context: Context): String {
        val map = LinkedHashMap(DEFAULTS)
        try {
            val file = File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)
            if (file.exists()) {
                file.readLines().forEach { raw ->
                    val line = raw.substringBefore('#').trim()
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim()
                        val v = line.substring(idx + 1).trim()
                        if (k in DEFAULTS) map[k] = v
                    }
                }
            }
        } catch (e: Exception) {
            // Flags must never break the boot start; fall back to what we have.
        }
        values = map
        return map.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }

    fun bool(key: String): Boolean = values[key] == "1" || values[key].equals("true", true)

    fun int(key: String): Int =
        values[key]?.toIntOrNull() ?: DEFAULTS[key]?.toIntOrNull() ?: 0
}
