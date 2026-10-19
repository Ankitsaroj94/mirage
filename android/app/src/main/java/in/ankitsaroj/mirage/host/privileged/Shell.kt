package `in`.ankitsaroj.mirage.host.privileged

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs commands as the current (shell) user. Only ever called from the privileged process. */
internal object Shell {
    /** stdout+stderr, trimmed. Never throws; a failure comes back as text. */
    fun run(vararg argv: String, timeoutMs: Long = 5_000): String = try {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroy()
        out.trim()
    } catch (t: Throwable) {
        "error: $t"
    }
}

/**
 * The global settings that turn on Android desktop windowing for external displays, which
 * includes our trusted virtual display. Originals are remembered and restored exactly — a
 * key that did not exist is deleted again rather than left at "0".
 *
 * The originals are also written to a file the shell user owns, so if the service process is
 * killed mid-session (Shizuku restarted, app force-stopped) the next start puts the tablet's
 * settings back before doing anything else.
 */
internal class DesktopSettings(private val backup: File = File("/data/local/tmp/mirage-desktop-settings.bak")) {

    /** Values we write. `force_resizable_activities` lets non-resizable apps open as windows. */
    val wanted = linkedMapOf(
        "enable_freeform_support" to "1",
        "force_desktop_mode_on_external_displays" to "1",
        "force_resizable_activities" to "1",
    )

    private var originals: Map<String, String?>? = null

    fun enable() {
        restoreStaleBackup()
        if (originals == null) {
            val saved = wanted.keys.associateWith { get(it) }
            originals = saved
            runCatching { backup.writeText(saved.entries.joinToString("\n") { "${it.key}=${it.value ?: NULL}" }) }
        }
        for ((key, value) in wanted) put(key, value)
    }

    fun restore() {
        val saved = originals ?: return restoreStaleBackup()
        apply(saved)
        originals = null
        backup.delete()
    }

    /** Puts back what a previous, killed service instance changed. */
    fun restoreStaleBackup() {
        if (originals != null || !backup.exists()) return
        val saved = backup.readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).takeUnless { it == NULL }
        }.toMap()
        apply(saved)
        backup.delete()
    }

    fun snapshot(): Map<String, String?> = wanted.keys.associateWith { get(it) }

    private fun apply(values: Map<String, String?>) {
        for ((key, value) in values) {
            if (value == null) Shell.run("settings", "delete", "global", key) else put(key, value)
        }
    }

    private fun get(key: String): String? = Shell.run("settings", "get", "global", key).takeUnless { it == "null" || it.startsWith("error") }
    private fun put(key: String, value: String) { Shell.run("settings", "put", "global", key, value) }

    private companion object { const val NULL = "<unset>" }
}
