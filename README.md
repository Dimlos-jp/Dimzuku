# Dimzuku

Dimzuku is an independent fork of Shizuku that does one thing: **start the Shizuku server over
wireless debugging as early as possible after boot, before the first unlock.**

It is based on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0),
thedjchi's fork, and BombPerry's Direct Boot fork (13.7.0 v2), whose work made pre-unlock start
possible in the first place. It is not affiliated with any of them.

What Dimzuku changes (details and measurements in [DIRECTBOOT_NOTES.md](DIRECTBOOT_NOTES.md)):

- **Finds the wireless-debugging port by scanning loopback**, instead of waiting for mDNS. adbd's
  mDNS registration is racy right after boot (error -65563) and slow even when it works.
- Staged discovery: one discovery for the whole run, short waits, a retry only when really needed.
- Prepares the adb key while waiting for Wi-Fi, polls the reconnect after `tcpip` every 200 ms.
- A boot log with `boot+<ms>` marks that you can read, share and delete in the app (see below).

Measured on a Pixel 7 (Android 14) on a home router: the start command finishes at **boot+18.7 to
20.1 s** instead of 25.1 s with the original code. Wi-Fi becoming usable is the floor and depends on
the device and the network (router ~15-17 s, phone tethering ~11.6 s on a Pixel 10); once Wi-Fi is
up, the start command needs about 1.5-2 s.

Notes: the application id is still `moe.shizuku.privileged.api` (the server identifies the manager
by it), so Dimzuku can't be installed next to the official Shizuku. Mainly tested on a Pixel 7
(Android 14); it also worked on a Pixel 10 (Android 17).

## Boot log

Dimzuku keeps a small boot log (timestamps, Wi-Fi and adb state, port numbers) in device-protected
storage on the phone, so it is written before the first unlock. It is never uploaded. The file is
rotated at 256 KB.

Open it from the home menu (three dots) > **Boot log**. **Share** and **Copy** mask IP addresses,
IPv6 addresses and the device serial number; the screen shows the raw log. **Clear log** deletes it.
If you report a problem with starting at boot, please attach the shared log.

Compared with the versions it builds on:

- The app's settings and adb key live in device-encrypted storage, so the start can run before the
  first unlock (BombPerry's Direct Boot fork).
- Removed: the experimental auto pairing pilot, the donation link, and the links to the upstream
  wiki, issue tracker, translation project and update feed. Bug reports and updates go to this
  repository.

License: Apache-2.0, see [LICENSE](LICENSE).
