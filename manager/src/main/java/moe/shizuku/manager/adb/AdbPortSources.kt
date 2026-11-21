package moe.shizuku.manager.adb

import android.os.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two ways to learn the wireless-debugging port that do NOT depend on mDNS:
 *
 *  1. logcat: adbd logs "adbwifi started on port N" a few ms after the toggle, even when its mDNS
 *     registration failed (error -65563). Needs android.permission.READ_LOGS, which an app can only
 *     get once through `adb shell pm grant <applicationId> android.permission.READ_LOGS`.
 *  2. loopback scan: the TLS server listens on an ephemeral port (32768..60999) on all interfaces.
 *     Every open port in that range is probed with an adb CNXN packet; adbd answers with STLS.
 *     Needs no permission. (service.adb.tls.port is not readable: SELinux, see the boot logs.)
 *
 * Whichever finds a port first calls [onPort]. All logging goes through [log].
 */
class AdbPortSources(
    private val log: (String) -> Unit,
    private val onPort: (Int) -> Unit,
) {

    // ---- logcat -----------------------------------------------------------------------------

    @Volatile private var armed = false
    @Volatile private var armedAt = 0L
    @Volatile private var logcatLines = 0
    @Volatile private var adbdLines = 0
    @Volatile private var logcatProc: Process? = null
    @Volatile private var logcatStopped = false

    /** Starts `logcat` once. Matching lines are only reported between [arm] calls and the port. */
    fun startLogcat() {
        val startedAt = SystemClock.elapsedRealtime()
        Thread({
            try {
                // -b all: adbd's lines are not in the main buffer on this device.
                val p = ProcessBuilder("logcat", "-b", "all", "-T", "1", "-v", "brief", "adbd:I", "*:S")
                    .redirectErrorStream(true)
                    .start()
                logcatProc = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (logcatLines++ == 0) log("logcat: first line after ${SystemClock.elapsedRealtime() - startedAt}ms")
                    if (line.contains("adbd")) adbdLines++
                    val m = LOGCAT_PORT.find(line)
                    if (m != null && armed) {
                        val port = m.groupValues[1].toIntOrNull()
                        if (port != null && port > 0) {
                            armed = false
                            log("logcat: port $port (+${SystemClock.elapsedRealtime() - armedAt}ms after arm)")
                            onPort(port)
                        }
                    }
                }
                if (!logcatStopped) log("logcat: reader ended (lines=$logcatLines) - READ_LOGS missing?")
            } catch (e: Throwable) {
                if (!logcatStopped) log("logcat: reader failed: $e")
            }
        }, "adb-logcat").apply { isDaemon = true }.start()
    }

    /** Call right BEFORE writing adb_wifi_enabled=1, so a port line from earlier is ignored. */
    fun arm() {
        armedAt = SystemClock.elapsedRealtime()
        armed = true
    }

    // ---- loopback scan ----------------------------------------------------------------------

    @Volatile private var scanGen = 0
    @Volatile private var scanHit = false
    @Volatile private var scanPasses = 0
    @Volatile private var scanOpenSeen = 0

    /** Scans repeatedly until [stopScan] or a hit. Call right after the toggle. */
    fun startScan() {
        val gen = ++scanGen
        scanHit = false
        Thread({ scanLoop(gen) }, "adb-scan").apply { isDaemon = true }.start()
    }

    fun stopScan() {
        scanGen++
    }

    private fun scanLoop(gen: Int) {
        val t0 = SystemClock.elapsedRealtime()
        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { r ->
            Thread(r, "adb-scan-w").apply { isDaemon = true }
        }
        try {
            var pass = 0
            while (gen == scanGen && !scanHit) {
                pass++
                val tp = SystemClock.elapsedRealtime()
                val next = AtomicInteger(PORT_MIN)
                val open = AtomicInteger(0)
                val tasks = (1..SCAN_THREADS).map {
                    Callable {
                        while (gen == scanGen && !scanHit) {
                            val p = next.getAndIncrement()
                            if (p > PORT_MAX) break
                            if (!isOpen(p)) continue
                            open.incrementAndGet()
                            val tOpen = SystemClock.elapsedRealtime()
                            val ok = probe(p)
                            log("scan: open port $p at +${tOpen - t0}ms, probe ${SystemClock.elapsedRealtime() - tOpen}ms ok=$ok")
                            if (ok && gen == scanGen && !scanHit) {
                                scanHit = true
                                log("scan: port $p (+${SystemClock.elapsedRealtime() - t0}ms after scan start, pass $pass; ${stats()})")
                                onPort(p)
                                break
                            }
                        }
                    }
                }
                pool.invokeAll(tasks)
                scanPasses = pass
                scanOpenSeen = open.get()
                if (pass == 1 || scanHit) {
                    log("scan: pass $pass took ${SystemClock.elapsedRealtime() - tp}ms, open ports in range=${open.get()}")
                }
                if (!scanHit && gen == scanGen) Thread.sleep(PASS_GAP_MS)
            }
        } catch (e: Throwable) {
            log("scan: failed: $e")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** Sends an adb CNXN; an adbd (TLS or plain) answers with STLS / AUTH / CNXN. */
    private fun probe(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = PROBE_TIMEOUT_MS
            val data = "host::\u0000".toByteArray()
            val cnxn = 0x4e584e43
            val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(cnxn).putInt(0x01000001).putInt(256 * 1024).putInt(data.size)
            buf.putInt(data.sumOf { it.toInt() and 0xff }).putInt(cnxn.inv())
            buf.put(data)
            s.getOutputStream().apply { write(buf.array()); flush() }
            val head = ByteArray(4)
            var n = 0
            val ins = s.getInputStream()
            while (n < 4) {
                val r = ins.read(head, n, 4 - n)
                if (r < 0) break
                n += r
            }
            n == 4 && String(head, Charsets.ISO_8859_1) in setOf("STLS", "AUTH", "CNXN")
        }
    } catch (e: Exception) {
        false
    }

    fun stats(): String =
        "logcat lines=$logcatLines adbd=$adbdLines, scan passes=$scanPasses open=$scanOpenSeen"

    fun stopAll() {
        scanGen++
        logcatStopped = true
        armed = false
        try { logcatProc?.destroy() } catch (_: Throwable) {}
    }

    companion object {
        private val LOGCAT_PORT = Regex("""adb\s?wifi started on port (\d+)""")
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val PORT_MIN = 32768
        private const val PORT_MAX = 60999
        private const val SCAN_THREADS = 64
        private const val CONNECT_TIMEOUT_MS = 300
        private const val PROBE_TIMEOUT_MS = 600
        private const val PASS_GAP_MS = 50L
    }
}
