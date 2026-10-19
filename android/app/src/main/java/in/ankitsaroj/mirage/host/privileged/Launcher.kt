package `in`.ankitsaroj.mirage.host.privileged

/**
 * Starts activities on our display. Done with `am` because we are the shell user: its
 * INTERNAL_SYSTEM_WINDOW grant passes `isCallerAllowedToLaunchOnDisplay` for any app, whereas a
 * normal app may only launch onto a shell-owned virtual display activities that declare
 * `allowEmbedded` — i.e. almost none.
 */
internal object Launcher {

    /**
     * "pkg" (its launcher activity) or "pkg/cls". Returns `am`'s output. [freeform] asks for a
     * resizable desktop window (WINDOWING_MODE_FREEFORM = 5) instead of fullscreen.
     */
    fun launch(packageOrComponent: String, displayId: Int, freeform: Boolean = false): String {
        val component = if ('/' in packageOrComponent) packageOrComponent else resolveLauncher(packageOrComponent)
            ?: return "error: no launcher activity in $packageOrComponent"
        val mode = if (freeform) arrayOf("--windowingMode", "5") else emptyArray()
        return Shell.run("am", "start", "--display", displayId.toString(), *mode, "-n", component)
    }

    /** The secondary-home activity (what Android starts on a display with system decorations). */
    fun launchHome(displayId: Int): String = Shell.run(
        "am", "start", "--display", displayId.toString(),
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.SECONDARY_HOME",
    )

    fun resolveLauncher(pkg: String): String? = Shell.run(
        "cmd", "package", "resolve-activity", "--brief",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", pkg,
    ).lineSequence().lastOrNull { '/' in it }?.trim()

    fun succeeded(amOutput: String): Boolean =
        !amOutput.contains("Error", ignoreCase = true) && !amOutput.startsWith("error")
}
