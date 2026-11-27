package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.service.DirectBootStartService
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.BootLog
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * The single boot entry point.
 *
 * It is directBootAware and listens for both boot broadcasts:
 *   - LOCKED_BOOT_COMPLETED: before first unlock. Only device-encrypted storage is available,
 *     which is where this fork keeps its settings and the adb key.
 *   - BOOT_COMPLETED: after first unlock. Acts as the fallback / confirmation run.
 *
 * Both hand off to [DirectBootStartService], which decides what is possible in the current phase,
 * skips the start if the server is already running, and never runs two starts at once.
 *
 * The user's "Start on boot" setting is this receiver's enabled state (see
 * ShizukuSettings.setStartOnBoot), so when that setting is off nothing here runs at all.
 */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) return

        // processAge: a large boot+ with a small processAge means a slow broadcast delivery
        val processAge = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        BootLog.log(context, "[receiver] $action user=${UserHandleCompat.myUserId()} " +
                "boot+${SystemClock.elapsedRealtime()}ms processAge=${processAge}ms")

        if (UserHandleCompat.myUserId() > 0) {
            BootLog.log(context, "[receiver] skip: secondary user")
            return
        }

        when (val mode = ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ADB, LaunchMethod.ROOT -> {
                // The reason is already in the log. Before unlock, BOOT_COMPLETED tries again.
                if (!DirectBootStartService.start(context)) {
                    if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                        BootLog.log(context, "[receiver] the start was refused - BOOT_COMPLETED will try again")
                    } else {
                        BootLog.log(context, "[receiver] the start was refused - nothing else will retry")
                    }
                }
            }
            else -> BootLog.log(context, "[receiver] skip: no launch mode saved yet (=$mode)")
        }

        // WatchdogService is not direct-boot-aware, so only start it once the user has unlocked.
        if (action == Intent.ACTION_BOOT_COMPLETED && ShizukuSettings.getWatchdog()) {
            WatchdogService.start(context)
        }
    }
}
