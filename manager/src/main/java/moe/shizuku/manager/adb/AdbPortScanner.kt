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
 * Finds the wireless-debugging (adbd TLS) port without mDNS.
 *
 * Why: right after `adb_wifi_enabled` is switched on, adbd starts listening within a few ms, but
 * its mDNS registration can fail (error -65563, mdnsd not running yet) and even a successful one
 * is only visible to discovery ~1 s later. `service.adb.tls.port` can't be read (SELinux) and
 * logcat needs a log-access consent that can't be given before unlock.
 *
 * How: adbd's TLS server listens on an ephemeral port (32768..60999) on all interfaces. Every
 * open port in that range on loopback is probed with an adb CNXN packet; adbd answers with STLS
 * (AUTH / CNXN for a plain adbd). Other apps' listening sockets don't answer like that.
 * Needs no permission beyond INTERNET. Measured on a Pixel 7: ~0.4-0.9 s for the first hit.
 *
 * Call [start] right after the toggle, [stop] when done. The first port found is reported once
 * through [onPort]; everything is logged through [log].
 */
class AdbPortScanner(
    private val log: (String) -> Unit,
    private val onPort: (Int) -> Unit,
) {

    @Volatile private var generation = 0
    @Volatile private var hit = false

    /** Scans repeatedly until [stop] or a hit. */
    fun start() {
        val gen = ++generation
        hit = false
        Thread({ scanLoop(gen) }, "adb-scan").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
    }

    private fun scanLoop(gen: Int) {
        val t0 = SystemClock.elapsedRealtime()
        val pool = Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "adb-scan-w").apply { isDaemon = true }
        }
        try {
            var pass = 0
            while (gen == generation && !hit) {
                pass++
                val tp = SystemClock.elapsedRealtime()
                val next = AtomicInteger(PORT_MIN)
                val open = AtomicInteger(0)
                val tasks = (1..THREADS).map {
                    Callable {
                        while (gen == generation && !hit) {
                            val p = next.getAndIncrement()
                            if (p > PORT_MAX) break
                            if (!isOpen(p)) continue
                            open.incrementAndGet()
                            val tOpen = SystemClock.elapsedRealtime()
                            val ok = probe(p)
                            log("scan: open port $p at +${tOpen - t0}ms, probe ${SystemClock.elapsedRealtime() - tOpen}ms ok=$ok")
                            if (ok && gen == generation && !hit) {
                                hit = true
                                log("scan: port $p (+${SystemClock.elapsedRealtime() - t0}ms after scan start, pass $pass)")
                                onPort(p)
                                break
                            }
                        }
                    }
                }
                pool.invokeAll(tasks)
                if (pass == 1 || hit) {
                    log("scan: pass $pass took ${SystemClock.elapsedRealtime() - tp}ms, open ports in range=${open.get()}")
                }
                if (!hit && gen == generation) Thread.sleep(PASS_GAP_MS)
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

    /** Sends an adb CNXN; an adbd answers with STLS / AUTH / CNXN. */
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

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val PORT_MIN = 32768
        private const val PORT_MAX = 60999
        private const val THREADS = 64
        private const val CONNECT_TIMEOUT_MS = 300
        private const val PROBE_TIMEOUT_MS = 600
        private const val PASS_GAP_MS = 50L
    }
}
