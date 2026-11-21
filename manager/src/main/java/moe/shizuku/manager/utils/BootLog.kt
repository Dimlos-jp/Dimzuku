package moe.shizuku.manager.utils

import android.content.Context
import android.os.SystemClock
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small boot-start log shared by [moe.shizuku.manager.receiver.BootCompleteReceiver] and
 * [moe.shizuku.manager.service.DirectBootStartService].
 *
 * Lines go to logcat (tag "ShizukuBoot") and are appended to a file in DEVICE-ENCRYPTED storage,
 * so they are written even before first unlock and survive reboots and adb authorization resets:
 *
 *     /data/user_de/0/<applicationId>/files/boot_start_log.txt
 *
 * Read it after unlocking with:
 *     adb shell run-as <applicationId> cat /data/user_de/0/<applicationId>/files/boot_start_log.txt
 *
 * The file is rotated to boot_start_log.old.txt once it passes [MAX_BYTES].
 */
object BootLog {

    const val TAG = "ShizukuBoot"
    private const val FILE_NAME = "boot_start_log.txt"
    private const val OLD_FILE_NAME = "boot_start_log.old.txt"
    private const val MAX_BYTES = 256 * 1024L

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** System property read that never throws. Empty string if unreadable (e.g. SELinux). */
    private fun prop(key: String): String = try {
        SystemProperties.get(key, "")
    } catch (e: Throwable) {
        "?"
    }

    /** One-line snapshot: ms since boot, the two adb switches, adbd state and tcp port. */
    fun snapshot(context: Context): String {
        val cr = context.contentResolver
        fun g(name: String) = try {
            Settings.Global.getInt(cr, name, -1).toString()
        } catch (e: Exception) {
            "?"
        }
        return "boot+${SystemClock.elapsedRealtime()}ms" +
                " adb_enabled=${g("adb_enabled")}" +
                " adb_wifi_enabled=${g("adb_wifi_enabled")}" +
                " init.svc.adbd='${prop("init.svc.adbd")}'" +
                " tcp_port='${prop("service.adb.tcp.port")}'"
    }

    /** Value of init.svc.adbd ("running", "stopped", "" if unreadable). */
    fun adbdState(): String = prop("init.svc.adbd")

    fun log(context: Context, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.i(TAG, msg)
        try {
            val dir = context.createDeviceProtectedStorageContext().filesDir
            val file = File(dir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                file.renameTo(File(dir, OLD_FILE_NAME))
            }
            val line = buildString {
                synchronized(tsFormat) { append(tsFormat.format(Date())) }
                append("  ")
                append(msg)
                if (tr != null) {
                    append("  | ")
                    append(tr.toString())
                }
                append('\n')
            }
            file.appendText(line)
        } catch (e: Exception) {
            // Logging must never break the boot start.
            Log.e(TAG, "failed to write boot log file: $e")
        }
    }
}
