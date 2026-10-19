package com.voxturr.mirage.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.voxturr.mirage.R
import com.voxturr.mirage.core.DisplayAdvertiser
import com.voxturr.mirage.core.HostHelloAck
import com.voxturr.mirage.core.PeerLink
import com.voxturr.mirage.core.PeerListener
import com.voxturr.mirage.ui.MainActivity
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * "Be a display": listens on an ephemeral TCP port, advertises `_mirage._tcp`, accepts one host
 * at a time and runs a [ReceiverSession]. Foreground (connectedDevice) so the link survives
 * while the screen is showing the Mac. Holds a low-latency Wi-Fi lock + partial wakelock only
 * while a host is connected.
 */
class ReceiverService : Service() {

    companion object {
        private const val ACTION_START = "com.voxturr.mirage.receiver.START"
        private const val ACTION_STOP = "com.voxturr.mirage.receiver.STOP"
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 0x4d31
        private const val TAG = "Mirage"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }

        /** Primary IPv4 address of the active network, for manual "Connect to IP…". */
        fun localIp(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }

    private lateinit var settings: ReceiverSettings
    /** Serialises lifecycle work off the main thread (sockets, NSD, teardown). */
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "mirage-receiver") }
    private val main = Handler(Looper.getMainLooper())

    private var listener: PeerListener? = null
    private var advertiser: DisplayAdvertiser? = null
    private var audio: AudioReceiver? = null
    private var session: ReceiverSession? = null
    private var port = 0
    private var ip: String? = null
    private var advertisedPanel: Panel? = null
    private var hostName: String? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = ReceiverSettings(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.receiver_channel), NotificationManager.IMPORTANCE_LOW))
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "mirage:receiver")
            ?.apply { setReferenceCounted(false) }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mirage:receiver")
            ?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            exec.execute { shutdown() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.receiver_starting), connected = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        exec.execute { if (listener == null) startListening() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exec.execute { shutdown() }
        exec.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the advertised TXT (px in the current orientation) truthful while idle.
        exec.execute {
            if (listener != null && session == null) {
                val panel = Panel.probe(this)
                if (panel != advertisedPanel) advertise(panel)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle (exec thread)
    // ---------------------------------------------------------------------------------------

    private fun startListening() {
        ReceiverHub.publish(ReceiverStatus.Waiting(null, 0, getString(R.string.receiver_starting)))
        try {
            settings = ReceiverSettings(this)
            audio = AudioReceiver()
            val l = PeerListener(0, settings.pairingCodeOrNull)
            l.onConnection = { link -> exec.execute { accept(link) } }
            l.start()
            listener = l
            port = l.port
            ip = localIp(this)
            advertise(Panel.probe(this))
            publishIdle(null)
        } catch (e: Throwable) {
            Log.e(TAG, "receiver failed to start", e)
            shutdown()
            ReceiverHub.publish(ReceiverStatus.Failed(e.message ?: e.javaClass.simpleName))
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private fun advertise(panel: Panel) {
        try {
            val adv = advertiser ?: DisplayAdvertiser(this).also { advertiser = it }
            if (advertisedPanel != null) adv.stop()
            adv.start(settings.deviceName, port, advertisementTxt(panel, settings.pairingCodeOrNull != null, audio = true))
            advertisedPanel = panel
        } catch (e: Throwable) {
            // Discovery is a convenience; manual IP:port still works.
            Log.w(TAG, "advertising failed", e)
        }
    }

    private fun accept(link: PeerLink) {
        val current = session
        if (current != null && !current.isEnded) {
            // One host at a time; a second connection is refused rather than fighting for the screen.
            Log.i(TAG, "refusing second host ${link.remoteAddress.hostAddress}")
            try { link.close() } catch (_: Exception) {}
            return
        }
        val a = audio ?: run { link.close(); return }
        val s = ReceiverSession(this, link, settings, a, sessionCallbacks)
        session = s
        ReceiverHub.session = s
        wifiLock?.acquire()
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        ReceiverHub.publish(ReceiverStatus.Connecting(ip, port, link.remoteAddress.hostAddress ?: "?"))
        try {
            s.start()
        } catch (e: Throwable) {
            Log.e(TAG, "session start failed", e)
            s.end(sendBye = false, error = e)
        }
    }

    private val sessionCallbacks = object : ReceiverSession.Callbacks {
        override fun onAccepted(session: ReceiverSession, ack: HostHelloAck) {
            hostName = ack.hostName
            ReceiverHub.publish(ReceiverStatus.Connected(ip, port, ack.hostName))
            main.post {
                notify(getString(R.string.receiver_connected, ack.hostName), connected = true)
                // Allowed while our UI is visible; otherwise the notification opens it.
                try {
                    startActivity(Intent(this@ReceiverService, ReceiverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (e: Exception) {
                    Log.w(TAG, "could not open the display screen", e)
                }
            }
        }

        override fun onRejected(session: ReceiverSession, reason: String) {
            exec.execute { publishIdle(reason) }
        }

        override fun onEnded(session: ReceiverSession, error: Throwable?) {
            exec.execute {
                if (this@ReceiverService.session === session) {
                    this@ReceiverService.session = null
                    ReceiverHub.session = null
                    if (wifiLock?.isHeld == true) wifiLock?.release()
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                if (listener != null) {
                    publishIdle(error?.let { "Disconnected: ${it.message ?: it.javaClass.simpleName}" })
                    val panel = Panel.probe(this@ReceiverService)
                    if (panel != advertisedPanel) advertise(panel)
                }
            }
        }
    }

    private fun publishIdle(message: String?) {
        ReceiverHub.publish(ReceiverStatus.Waiting(ip, port, message))
        main.post { notify(getString(R.string.receiver_waiting, ip ?: "?", port), connected = false) }
    }

    private fun shutdown() {
        session?.end(sendBye = true)
        session = null
        ReceiverHub.session = null
        try { advertiser?.stop() } catch (_: Throwable) {}
        advertiser = null
        advertisedPanel = null
        try { listener?.stop() } catch (_: Throwable) {}
        listener = null
        audio?.close()
        audio = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        ReceiverHub.publish(ReceiverStatus.Stopped)
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private fun notify(text: String, connected: Boolean) {
        if (listener == null && !connected) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, connected))
    }

    private fun buildNotification(text: String, connected: Boolean): Notification {
        val target = if (connected) ReceiverActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(this, 0, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mirage)
            .setContentTitle(getString(R.string.receiver_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.receiver_stop), stop).build())
            .build()
    }
}
