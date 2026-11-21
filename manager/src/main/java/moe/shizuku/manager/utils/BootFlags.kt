package moe.shizuku.manager.utils

import android.content.Context
import java.io.File

/**
 * Experiment flags for the boot-start path, read from a plain text file in DEVICE-ENCRYPTED
 * storage so they can be read before first unlock and changed without rebuilding:
 *
 *     /data/user_de/0/<applicationId>/files/boot_experiment_flags.txt
 *
 * One `key=value` per line, `#` starts a comment. Missing keys use the defaults below. The file
 * is re-read at the start of every boot-start run, and the effective values are logged.
 *
 * Set flags from a PC (debug builds only, because of run-as). Use the absolute DE path, because
 * a relative `files/` is the credential-encrypted directory:
 *     adb shell run-as <applicationId> sh -c "printf 'skip_adb_enabled=1\n' > /data/user_de/0/<applicationId>/files/boot_experiment_flags.txt"
 *
 * Flags (default in brackets):
 *   prewarm_key=1          Build AdbKey + SSLContext in parallel while waiting for Wi-Fi.
 *   fast_toggle=1          Only reset adb_wifi_enabled to 0 if it is currently 1, and skip the
 *                          fixed 500 ms gap when it was already 0 (retries use a short gap).
 *   staged_discovery=1     Start mDNS discovery once, before the first toggle, keep it running
 *                          across attempts, and use short staged timeouts instead of 15 s x 4.
 *   wifi_settle_ms=500     Extra wait after Wi-Fi has an IP address (the old fixed value was 1500).
 *   skip_adb_enabled=0     Do NOT write adb_enabled=1 (neither from the app nor in the adb shell).
 *                          EXPERIMENT: check that the server still survives, see verify log.
 *   tcpip_after_start=0    In TCP mode, run the starter first and send `tcpip:` afterwards.
 *                          EXPERIMENT: restarting adbd may kill the server (cgroup), see verify log.
 *   use_tls_port_prop=0    Also poll the system property service.adb.tls.port and use it if it
 *                          shows up before mDNS does. The value is always LOGGED regardless.
 *   verify_delay_ms=10000  How long after the start command to log the survival check.
 */
object BootFlags {

    private const val FILE_NAME = "boot_experiment_flags.txt"

    private val DEFAULTS = linkedMapOf(
        "prewarm_key" to "1",
        "fast_toggle" to "1",
        "staged_discovery" to "1",
        "wifi_settle_ms" to "500",
        "skip_adb_enabled" to "0",
        "tcpip_after_start" to "0",
        "use_tls_port_prop" to "0",
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
