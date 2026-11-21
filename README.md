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
- A timeline log with `boot+<ms>` marks and experiment flags, both in device-encrypted storage.

Measured on a Pixel 7 (Android 14): start command finished at **boot+18.7 s** instead of 25.1 s;
Wi-Fi itself is usable at ~boot+17.6 s, which is the floor.

Notes: the application id is still `moe.shizuku.privileged.api` (the server identifies the manager
by it), so Dimzuku can't be installed next to the official Shizuku. Tested on one device only.

Compared with the versions it builds on:

- The app's settings and adb key live in device-encrypted storage, so the start can run before the
  first unlock (BombPerry's Direct Boot fork).
- Removed: the experimental auto pairing pilot, the donation link, and the links to the upstream
  wiki, issue tracker, translation project and update feed. Bug reports and updates go to this
  repository.

License: Apache-2.0, see [LICENSE](LICENSE).
