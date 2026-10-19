package com.voxturr.mirage.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress

// DNS-SD "_mirage._tcp" via NsdManager — spec/PROTOCOL.md §1.

const val SERVICE_TYPE = "_mirage._tcp"
private const val TAG = "MirageDiscovery"

data class DiscoveredDisplay(
    val name: String,
    val host: InetAddress,
    val port: Int,
    val txt: Map<String, String>,
) {
    val platform: String get() = txt["os"] ?: "macos"
    val requiresPairingCode: Boolean get() = txt["lock"] == "1"
    val resolutionSummary: String get() = "${txt["px"] ?: "?"} @ ${txt["hz"] ?: "60"}Hz"
}

/**
 * Names this process is advertising right now, so a browser in the same app (the tablet can
 * be both "display" and "send my screen" at once) does not list itself.
 */
private val localServiceNames: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

/** Advertises this device as an available display. */
class DisplayAdvertiser(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    @Volatile private var _registeredName: String? = null

    /** The name NSD actually registered — it appends " (2)" etc. on a conflict. Null until registered. */
    val registeredName: String? get() = _registeredName

    fun start(displayName: String, port: Int, txt: Map<String, String>) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _registeredName = info.serviceName
                localServiceNames += info.serviceName
                Log.i(TAG, "advertising \"${info.serviceName}\" on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        _registeredName?.let { localServiceNames -= it }
        _registeredName = null
        try { nsd.unregisterService(l) } catch (_: IllegalArgumentException) {}
    }
}

/**
 * Finds displays (Macs or tablets) offering themselves. Callback on the main thread.
 *
 * All state is confined to the main thread: NSD callbacks are hopped there before touching it.
 * Resolution uses registerServiceInfoCallback on API 34+, which keeps each entry live (TXT and
 * address updates, e.g. a tablet rotating changes `px`). API 33 only has resolveService, which
 * fails with FAILURE_ALREADY_ACTIVE if called while another resolve runs, so it is queued.
 */
class DisplayBrowser(context: Context) {
    var onUpdate: ((List<DiscoveredDisplay>) -> Unit)? = null

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val displays = LinkedHashMap<String, DiscoveredDisplay>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var generation = 0

    // API 34+: one live callback per service name.
    private val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    // API 33: resolveService one at a time.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() = onMain {
        stopNow()
        val gen = ++generation
        // Many Wi-Fi drivers drop multicast to save power unless someone holds this lock;
        // without it mDNS responses simply never arrive.
        multicastLock = appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("mirage-browse")?.apply { setReferenceCounted(false); acquire() }

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = onMain { if (gen == generation) found(info) }
            override fun onServiceLost(info: NsdServiceInfo) = onMain { if (gen == generation) lost(info.serviceName) }
        }
        discovery = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() = onMain { stopNow() }

    private fun stopNow() {
        generation++
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: IllegalArgumentException) {} }
        discovery = null
        if (Build.VERSION.SDK_INT >= 34) {
            infoCallbacks.values.forEach { unregisterInfoCallback(it) }
        }
        infoCallbacks.clear()
        resolveQueue.clear()
        resolving = false
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        if (displays.isNotEmpty()) { displays.clear(); publish() }
    }

    private fun found(info: NsdServiceInfo) {
        if (!info.serviceType.contains(SERVICE_TYPE)) return
        if (info.serviceName in localServiceNames) return
        if (Build.VERSION.SDK_INT >= 34) {
            if (info.serviceName !in infoCallbacks) registerInfoCallback(info)
        } else {
            if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info)
            resolveNext()
        }
    }

    private fun lost(name: String) {
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.remove(name)?.let { unregisterInfoCallback(it) }
        resolveQueue.removeAll { it.serviceName == name }
        if (displays.remove(name) != null) publish()
    }

    private fun resolved(name: String, host: InetAddress?, port: Int, attributes: Map<String, ByteArray?>) {
        if (host == null || port <= 0 || name in localServiceNames) return
        val display = DiscoveredDisplay(name, host, port, decodeTxt(attributes))
        if (displays[name] != display) { displays[name] = display; publish() }
    }

    private fun publish() {
        val list = displays.values.sortedBy { it.name }
        onUpdate?.invoke(list)
    }

    @RequiresApi(34)
    private fun registerInfoCallback(info: NsdServiceInfo) {
        val gen = generation
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "resolve of ${info.serviceName} failed: $errorCode")
                if (gen == generation) infoCallbacks.remove(info.serviceName)
            }
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                if (gen != generation) return
                // Prefer IPv4: link-local IPv6 needs a scope id that Socket handling often loses.
                val host = updated.hostAddresses.firstOrNull { it is Inet4Address } ?: updated.hostAddresses.firstOrNull()
                resolved(info.serviceName, host, updated.port, updated.attributes)
            }
            override fun onServiceLost() {
                if (gen == generation && displays.remove(info.serviceName) != null) publish()
            }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks[info.serviceName] = callback
        try {
            nsd.registerServiceInfoCallback(info, appContext.mainExecutor, callback)
        } catch (e: IllegalArgumentException) {
            infoCallbacks.remove(info.serviceName)
            Log.w(TAG, "registerServiceInfoCallback: ${e.message}")
        }
    }

    @RequiresApi(34)
    private fun unregisterInfoCallback(callback: NsdManager.ServiceInfoCallback) {
        try { nsd.unregisterServiceInfoCallback(callback) } catch (_: IllegalArgumentException) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val gen = generation
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = onMain {
                if (gen != generation) return@onMain
                Log.w(TAG, "resolve of ${info.serviceName} failed: $errorCode")
                resolving = false
                resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) = onMain {
                if (gen != generation) return@onMain
                resolving = false
                resolved(next.serviceName, info.host, info.port, info.attributes)
                resolveNext()
            }
        })
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** TXT attribute bytes → strings; a key without a value becomes "". */
internal fun decodeTxt(attributes: Map<String, ByteArray?>): Map<String, String> =
    attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
