# Starting the server on boot, before unlock

Goal: start Shizuku's server on device boot, **before** the phone is unlocked where the
device allows it, and as soon as possible after unlock otherwise.

## How it works

- `manager/.../receiver/BootCompleteReceiver.kt` is the single boot entry point. It is
  `directBootAware` and listens for both `LOCKED_BOOT_COMPLETED` (before first unlock) and
  `BOOT_COMPLETED` (after unlock). The "Start on boot" setting toggles this receiver, so
  nothing runs when that setting is off.
- `manager/.../service/DirectBootStartService.kt` is a `directBootAware` foreground service
  that does the actual start, without WorkManager (WorkManager / JobScheduler do not run
  before first unlock):
  1. After unlock, if the server is already running (binder alive), stop: no restart.
  2. Root launch mode: run the starter through `su`.
  3. ADB launch mode with a fixed TCP port: connect over loopback, no Wi-Fi or mDNS needed.
  4. ADB launch mode with wireless debugging: wait for Wi-Fi, toggle `adb_wifi_enabled`
     0 → 1, find the port (AdbPortScanner and mDNS, whichever answers first), retry a few times.
  5. Before unlock, finish as soon as the start command is sent (the binder can only be
     delivered after unlock). After unlock, wait for the binder.
  - If a run is still in progress when the next boot broadcast arrives, the new request is
    ignored and the running attempt continues, so there is never a double start.
  - If an attempt fails after unlock, it hands over to the normal `AdbStartWorker` path,
    which retries with backoff and shows the "Attempt now" notification.
- The app's settings and adb key live in device-encrypted storage, so all of this can read
  them before unlock.

## Log file

Every step is written to a device-encrypted log file, so it survives reboots, first unlock
and adb authorization resets:

```
/data/user_de/0/<applicationId>/files/boot_start_log.txt
```

Read it after unlocking:

```
adb shell run-as <applicationId> cat /data/user_de/0/<applicationId>/files/boot_start_log.txt
```

It rotates to `boot_start_log.old.txt` after 256 KB.

## Why wireless debugging looked "dead" before unlock

The first tests (Asus ROG Phone 7, Nov 2026) found no wireless adb port open while
locked, which looked like adbd refusing to listen before unlock. Dimlos found the actual
cause: `AdbDebuggingManager` evaluates `adb_wifi_enabled` only at the moment the setting
changes. If the device isn't connected to a trusted Wi-Fi network at that moment, it logs
"Not connected to any wireless network. Not enabling adbwifi." and never retries. Early in a
locked boot Wi-Fi usually isn't up yet, so the setting was evaluated too early.

Waiting for Wi-Fi and then toggling `adb_wifi_enabled` 0 → 1 makes the framework
re-evaluate, and the start can then succeed before unlock.

Requirements for the wireless path:
- Wireless debugging was enabled on this network before, with "Always allow on this
  network" ticked. Otherwise Android needs a confirmation prompt, which can't be shown on
  the lock screen.
- The device connects to that Wi-Fi network before unlock (most do for ordinary saved
  networks, not always for enterprise ones).

Other notes:
- Classic `adb tcpip` does not survive a reboot without `persist.adb.tcp.port`, which an
  unrooted app can't set. `service.adb.tcp.port` is only the runtime copy and is cleared
  on reboot.
- Starting the server over adb from a PC while locked works, and the server keeps running
  after the PC and Wi-Fi are disconnected, so key authorization and the server launch both
  work before unlock.

## Timing log and experiment flags

Every run writes `T+<ms>` timeline marks, each with `boot+<ms>`, `adb_enabled`,
`adb_wifi_enabled`, `init.svc.adbd` and the `service.adb.tcp.port` property. The receiver logs `processAge`, which tells
"slow broadcast delivery" apart from "slow start in our own code".

Behaviour is switched by `boot_experiment_flags.txt` in the same device-encrypted `files/`
directory as the log (see `BootFlags.kt` for every flag and its default). The effective values are
logged at the start of each run as `flags: ...`.

Survival check: `VERIFY(...)` is logged `verify_delay_ms` after the start command, and the
BOOT_COMPLETED run logs `server already running` if the binder is alive after unlock. Together
with `init.svc.adbd` these show whether the server survives `adb_wifi_enabled=0`, adbd stopping
(`skip_adb_enabled=1`) and adbd restarting (`tcpip_after_start=1`).

## Finding the wireless-debugging port (AdbPortScanner)

Measured on a Pixel 7 (Android 14), with logcat of adbd and system_server:

- After `adb_wifi_enabled=1`, adbd logs `adbwifi started on port N` within ~5-20 ms.
- Its mDNS registration is racy: on some boots it fails with error -65563
  (`kDNSServiceErr_ServiceNotRunning`, mdnsd not running), so the service is never discoverable
  until the next toggle. Starting our own NsdManager discovery early does not fix it: on
  Android 14 discovery runs in system_server (`MdnsDiscoveryManager`) and does not start mdnsd.
  Even when registration succeeds, discovery sees the service only ~0.9 s later.
- `service.adb.tls.port` can't be read by an app (SELinux denial, `adbd_prop`).
- logcat of other processes is not usable before unlock: even with `READ_LOGS` granted the
  reader got no adbd line (Android asks for log-access consent).

So `AdbPortScanner` connects to every port in 32768-60999 on loopback and probes the open ones
with an adb CNXN packet (adbd answers STLS). It needs no permission. mDNS stays running as a
fallback; whichever reports a port first wins. A failed mDNS registration no longer costs a
re-toggle.

In about one run in three, the first toggle produces no listener at all (0 open ports for 1.4 s);
the re-toggle then fixes it and costs about 1.5-2 s. Waiting longer than 1.4 s would not help:
in the logs the port never showed up on its own between 1.4 s and 2 s.

## Results (Pixel 7, Android 14, boot to "start command finished")

| Version | boot + | Notes |
|---|---|---|
| original (4 x 15 s discovery windows) | 25.1 s | first toggle fails -> 2nd attempt |
| staged discovery, settle 1000 ms | 22.0 s | |
| staged discovery, settle 500 ms | 21.6 s | first toggle fails (mDNS -65563) |
| + port scan, settle 500 ms | 19.6 s | first attempt succeeds |
| + port scan, settle 0 | **18.65 s** (best), typically 18.7-19.7 s | Wi-Fi is up at ~17.6 s |
| same, first toggle fails | 20.1-21.9 s | re-toggle needed |

Wi-Fi becoming usable (~T+3.4 s after the service starts, ~boot+17.6 s) is the floor; the
remaining ~1 s is the wait for adbd's port (0.4-1.3 s), TLS connect, tcpip restart and the starter. The server
was alive (`binder alive`) after every unlock.

### The floor depends on the network

Wi-Fi becoming usable is not under this app's control, so it varies with device and network.
On a Pixel 10 (Android 17) the same build gave: home router, Wi-Fi at boot+15.3-17.4 s and start
command finished at boot+17.1-19.3 s (5 runs); phone tethering only (router auto-connect off),
Wi-Fi at boot+11.7 s and finished at boot+13.7 s (1 run, first toggle succeeded). From Wi-Fi
available to "start command finished" is 1.5-2 s in both cases. Do not read the router/tether
difference as a property of the code; why this router is slower was not investigated. A network
that is not set to "Always allow" for wireless debugging cannot work before unlock (the allow
prompt needs a tap): that run took 25.5 s here and only succeeded after retries.
