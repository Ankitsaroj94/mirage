package `in`.ankitsaroj.mirage.host.privileged

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * A Context that says "I am com.android.shell" — scrcpy's FakeContext.
 *
 * Code here runs as uid 2000 (shell), either inside a Shizuku UserService or under a bare
 * `app_process` for the spike. System services validate the package name they are handed
 * against the calling uid, so DisplayManager and friends must see "com.android.shell", never
 * our app's package (Shizuku's own context would report that, and be refused).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class ShellContext private constructor(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = PACKAGE
    override fun getOpPackageName(): String = PACKAGE
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE).build()
    override fun getDeviceId(): Int = 0
    override fun getApplicationContext(): Context = this

    companion object {
        const val PACKAGE = "com.android.shell"

        val instance: ShellContext by lazy {
            // Hidden-API checks are usually off for app_process, but a Shizuku service is
            // started in ways that have changed between versions; exempting is cheap.
            HiddenApiBypass.addHiddenApiExemptions("L")
            ShellContext(systemContext())
        }

        /**
         * The system Context of this process's ActivityThread, creating a bare one (no
         * `attach`) when none exists — the trick scrcpy uses. `ActivityThread.systemMain()`
         * would also work but runs system_server-only setup we do not want under shell.
         */
        private fun systemContext(): Context {
            val cls = Class.forName("android.app.ActivityThread")
            var thread = cls.getDeclaredMethod("currentActivityThread").invoke(null)
            if (thread == null) {
                // ActivityThread's constructor builds a Handler, which needs a Looper.
                if (Looper.getMainLooper() == null) {
                    @Suppress("DEPRECATION") Looper.prepareMainLooper()
                }
                val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
                thread = ctor.newInstance()
                cls.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
                // Samsung's CompatSandbox asks the thread's ConfigurationController for the
                // configuration while building the system context; a bare thread has none
                // (NPE in ActivityThread.getConfiguration). scrcpy works around it the same way.
                runCatching {
                    val internal = Class.forName("android.app.ActivityThreadInternal")
                    val controller = Class.forName("android.app.ConfigurationController")
                        .getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                    cls.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
                }
            }
            return cls.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        }
    }
}
