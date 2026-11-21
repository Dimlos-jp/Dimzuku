package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Pair<String, Int>>
) {

    /** Optional trace of discovery and resolve events, for the boot log. */
    var debug: ((String) -> Unit)? = null

    // A failed resolve is retried; the discovery does not report the service again
    private val handler = Handler(Looper.getMainLooper())
    private val resolveAttempts = ConcurrentHashMap<String, Int>()

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        debug?.invoke("mDNS: service found name=${info.serviceName}")
        resolve(info)
    }

    private fun resolve(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this, info))
    }

    private fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
        val attempt = (resolveAttempts[info.serviceName] ?: 0) + 1
        debug?.invoke("mDNS: resolve FAILED name=${info.serviceName} code=$errorCode (attempt $attempt)")
        if (running && attempt <= MAX_RESOLVE_ATTEMPTS) {
            resolveAttempts[info.serviceName] = attempt
            handler.postDelayed({
                if (running) runCatching { resolve(info) }
                    .onFailure { debug?.invoke("mDNS: resolve retry threw $it") }
            }, RESOLVE_RETRY_MS)
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged("" to -1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress
        if (host == null) {
            debug?.invoke("mDNS: resolved without host name=${resolvedService.serviceName}")
            return
        }
        val isLocal = NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .any { networkInterface ->
                networkInterface.inetAddresses
                    .asSequence()
                    .any { host == it.hostAddress }
            }
        val portInUse = isPortAvailable(host, resolvedService.port)
        debug?.invoke("mDNS: resolved name=${resolvedService.serviceName} host=$host port=${resolvedService.port} " +
                "running=$running local=$isLocal portInUse=$portInUse")
        if (running && isLocal && portInUse) {
            serviceName = resolvedService.serviceName
            observer.onChanged(host to resolvedService.port)
        }
    }

    private fun isPortAvailable(host: String, port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress(host, port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.debug?.invoke("mDNS: discovery started")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.debug?.invoke("mDNS: start discovery FAILED code=$errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.debug?.invoke("mDNS: service lost name=${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val requested: NsdServiceInfo,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            adbMdns.onResolveFailed(requested, i)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
        private const val MAX_RESOLVE_ATTEMPTS = 5
        private const val RESOLVE_RETRY_MS = 200L
    }
}
