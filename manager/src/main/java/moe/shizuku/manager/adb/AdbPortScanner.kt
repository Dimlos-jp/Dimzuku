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
 * Finds the wireless-debugging (adbd TLS) port without mDNS. adbd listens within milliseconds of
 * `adb_wifi_enabled=1`, but its mDNS registration can fail right after boot (error -65563) and is
 * visible to discovery only ~1 s later; `service.adb.tls.port` is not readable by apps and logcat
 * is not available before unlock.
 *
 * The TLS server listens on an ephemeral port on all interfaces. Every open loopback port in
 * 32768..60999 is probed with an adb CNXN packet; adbd answers STLS (AUTH / CNXN for a plain adbd).
 *
 * Scan order ([buildOrder]): every port is still scanned, only the order changes. adbd binds port
 * 0 and lets the kernel choose; on Linux 4.6+ that gives an odd port from the lower half of the
 * default ip_local_port_range (BombPerry's notes trace it to inet_csk_find_open_port). On a
 * Pixel 7 (Android 14) all 18 ports found in one boot log were odd and within 32769..46883. So the
 * order is: odd ports of the lower half, the other odd ports, then the even ports.
 *
 * Call [start] right after the toggle and [stop] when done. The first port found is reported once
 * through [onPort].
 */
class AdbPortScanner(
    private val log: (String) -> Unit,
    private val onPort: (Int) -> Unit,
) {

    private val order: IntArray = buildOrder()

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
                val next = AtomicInteger(0)
                val open = AtomicInteger(0)
                val tasks = (1..THREADS).map {
                    Callable {
                        while (gen == generation && !hit) {
                            val i = next.getAndIncrement()
                            if (i >= order.size) break
                            val p = order[i]
                            if (!isOpen(p)) continue
                            open.incrementAndGet()
                            val tOpen = SystemClock.elapsedRealtime()
                            val ok = probe(p)
                            log("scan: open port $p at +${tOpen - t0}ms, probe ${SystemClock.elapsedRealtime() - tOpen}ms ok=$ok")
                            if (ok && gen == generation && !hit) {
                                hit = true
                                log("scan: port $p (+${SystemClock.elapsedRealtime() - t0}ms after scan start, pass $pass, position $i of ${order.size})")
                                onPort(p)
                                break
                            }
                        }
                    }
                }
                pool.invokeAll(tasks)
                if (pass == 1 || hit) {
                    log("scan: pass $pass took ${SystemClock.elapsedRealtime() - tp}ms, open ports in range=${open.get()}, reached ${minOf(next.get(), order.size)} of ${order.size}")
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

        /**
         * Scan order, first to last: odd ports of the lower half of the range, the other odd
         * ports, then the even ports. The lower half is computed the way the kernel does it,
         * low + (((high + 1 - low) >> 2) << 1), which is 32768..46883 for the default range.
         * Every port in PORT_MIN..PORT_MAX appears exactly once.
         */
        internal fun buildOrder(): IntArray {
            val out = IntArray(PORT_MAX - PORT_MIN + 1)
            var n = 0
            fun add(from: Int, toExclusive: Int, odd: Boolean) {
                var p = if ((from % 2 == 1) == odd) from else from + 1
                while (p < toExclusive) {
                    out[n++] = p
                    p += 2
                }
            }
            val half = PORT_MIN + (((PORT_MAX + 1 - PORT_MIN) shr 2) shl 1)
            add(PORT_MIN, half, odd = true)
            add(half, PORT_MAX + 1, odd = true)
            add(PORT_MIN, PORT_MAX + 1, odd = false)
            return out
        }

        private const val THREADS = 64
        private const val CONNECT_TIMEOUT_MS = 300
        private const val PROBE_TIMEOUT_MS = 600
        private const val PASS_GAP_MS = 50L
    }
}
