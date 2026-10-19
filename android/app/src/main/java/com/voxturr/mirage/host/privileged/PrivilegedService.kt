package com.voxturr.mirage.host.privileged

import androidx.annotation.Keep
import com.voxturr.mirage.host.HostSession
import com.voxturr.mirage.host.ServerArgs
import kotlin.system.exitProcess

/**
 * The Shizuku UserService: Shizuku starts it in its own process as the shell user with our
 * APK on the classpath — the same situation as [ServerMain] under adb — so it simply hosts a
 * [HostSession]. The app drives it over binder and polls [status]/[stats].
 *
 * If the app dies, Shizuku kills this process too; the display dies with it, and
 * DesktopSettings restores anything it changed on the next start from its backup file.
 */
@Keep
class PrivilegedService : IPrivilegedService.Stub() {

    private var session: HostSession? = null
    @Volatile private var status = HostSession.statusLine(HostSession.State.Idle)
    @Volatile private var stats = ""

    init {
        DesktopSettings().restoreStaleBackup()
    }

    override fun destroy() {
        stopHost()
        exitProcess(0)
    }

    @Synchronized
    override fun startHost(args: Array<String>): String {
        val parsed = try {
            ServerArgs.parse(args)
        } catch (e: IllegalArgumentException) {
            return "ERR ${e.message}"
        }
        session?.stopAndWait()
        stats = ""
        val name = parsed.hostName
            ?: Shell.run("settings", "get", "global", "device_name").takeUnless { it.isBlank() || it == "null" || it.startsWith("error") }
            ?: android.os.Build.MODEL
        val s = HostSession(parsed.options, name)
        s.listener = object : HostSession.Listener {
            override fun onState(state: HostSession.State) {
                if (session === s) status = HostSession.statusLine(state)
            }
            override fun onStats(stats: HostSession.Stats) {
                if (session === s) this@PrivilegedService.stats = HostSession.statsLine(stats)
            }
        }
        session = s
        status = HostSession.statusLine(HostSession.State.Connecting("${parsed.host}:${parsed.port}"))
        s.connect(parsed.host, parsed.port, parsed.pairingCode)
        return status
    }

    @Synchronized
    override fun stopHost() {
        session?.stopAndWait()
        session = null
        status = HostSession.statusLine(HostSession.State.Ended)
        stats = ""
    }

    override fun status(): String = status
    override fun stats(): String = stats

    override fun launchApp(packageOrComponent: String, displayId: Int): Boolean =
        Launcher.succeeded(Launcher.launch(packageOrComponent, displayId, freeform = true))
}
