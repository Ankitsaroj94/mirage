package `in`.ankitsaroj.mirage.host.privileged

import `in`.ankitsaroj.mirage.host.HostSession
import `in`.ankitsaroj.mirage.host.ServerArgs
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * The host server started by the Mac over adb — the primary way to get shell privileges, no
 * Shizuku needed (scrcpy's model):
 *
 * ```
 * APK=$(adb shell pm path `in`.ankitsaroj.mirage | sed -n 's/^package://p' | head -1)
 * adb shell CLASSPATH=$APK app_process / `in`.ankitsaroj.mirage.host.privileged.ServerMain \
 *     --host <mac ip> --port <mac port> [--code <pairing code>] [options, see ServerArgs]
 * ```
 * It connects out to the Mac's "Be a display" listener and runs the whole [HostSession] here,
 * as uid 2000. Status goes to stdout, one line each, flushed (see [HostSession.statusLine]):
 * `CONNECTING ip:port`, `CONNECTED`, `OK <displayId> <w>x<h> <codec> <fps>` (again after each
 * reconfigure), `STATS …` once a second, then `BYE` (exit 0) or `ERR <reason>` (exit 1).
 *
 * Stop it by closing its stdin (EOF) — the clean path: bye to the Mac, display released,
 * settings restored. SIGTERM/SIGINT run the same teardown from a shutdown hook. A hard kill
 * loses nothing permanent: the display dies with the process, and any desktop settings are
 * restored from DesktopSettings' backup file by the next server start.
 */
object ServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = try {
            ServerArgs.parse(args)
        } catch (e: IllegalArgumentException) {
            emit("ERR ${e.message}")
            exitProcess(2)
        }
        DesktopSettings().restoreStaleBackup()
        // Build the shell Context here, on the main thread: creating it prepares the main Looper,
        // which throws on any thread that already has one — like the encoder's HandlerThread,
        // where the first display would otherwise trigger it.
        ShellContext.instance

        val name = parsed.hostName
            ?: Shell.run("settings", "get", "global", "device_name").takeUnless { it.isBlank() || it == "null" || it.startsWith("error") }
            ?: android.os.Build.MODEL
        val session = HostSession(parsed.options, name)
        val finished = CountDownLatch(1)
        var exitCode = 0
        session.listener = object : HostSession.Listener {
            override fun onState(state: HostSession.State) {
                emit(HostSession.statusLine(state))
                when (state) {
                    is HostSession.State.Failed -> { exitCode = 1; finished.countDown() }
                    HostSession.State.Ended -> finished.countDown()
                    else -> Unit
                }
            }
            override fun onStats(stats: HostSession.Stats) = emit(HostSession.statsLine(stats))
        }

        Runtime.getRuntime().addShutdownHook(Thread { session.stopAndWait() })
        // stdin EOF = the Mac (or adb) went away: shut down cleanly.
        Thread({
            runCatching { while (System.`in`.read() >= 0) Unit }
            session.stop()
        }, "mirage-stdin").apply { isDaemon = true }.start()

        session.connect(parsed.host, parsed.port, parsed.pairingCode)
        finished.await()
        session.stopAndWait()
        exitProcess(exitCode)
    }

    @Synchronized
    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }
}
