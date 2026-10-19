package `in`.ankitsaroj.mirage.host

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import ankitsaroj.mirage.ipc.IPrivilegedService
import `in`.ankitsaroj.mirage.host.privileged.PrivilegedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * App-side handle on Shizuku and our [PrivilegedService]. Process-wide singleton because the
 * service process is too: the host activity, the session and the display launcher share one.
 *
 * Lifecycle: Shizuku running → permission granted → service bound. Any step can regress
 * (Shizuku is stopped on reboot), and [state] follows it.
 */
object ShizukuConnector {

    sealed interface State {
        /** Shizuku app missing or not started (it must be started after each reboot). */
        data object NotRunning : State
        data object NeedsPermission : State
        data object PermissionDenied : State
        data object Binding : State
        data class Ready(val service: IPrivilegedService) : State
        data class Failed(val message: String) : State
    }

    private const val PERMISSION_REQUEST = 4242
    private val _state = MutableStateFlow<State>(State.NotRunning)
    val state: StateFlow<State> = _state

    val service: IPrivilegedService? get() = (_state.value as? State.Ready)?.service

    private var args: Shizuku.UserServiceArgs? = null
    private var started = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _state.value = State.Failed("The privileged service did not start.")
                return
            }
            _state.value = State.Ready(IPrivilegedService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _state.value = if (Shizuku.pingBinder()) State.Binding else State.NotRunning
        }
    }

    /** Call once (idempotent); listeners are sticky, so it works before or after Shizuku starts. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        args = Shizuku.UserServiceArgs(ComponentName(app.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            // Shizuku reuses a running service of the same version. Tie it to the install time
            // so a reinstalled APK never talks to a stale service built from old code.
            .version(versionOf(app))
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { _state.value = State.NotRunning }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code != PERMISSION_REQUEST) return@addRequestPermissionResultListener
            if (result == PackageManager.PERMISSION_GRANTED) bind() else _state.value = State.PermissionDenied
        }
    }

    /** Re-reads Shizuku's state; binds when everything is in place. */
    fun refresh() {
        if (!Shizuku.pingBinder()) { _state.value = State.NotRunning; return }
        if (Shizuku.isPreV11()) { _state.value = State.Failed("Shizuku is too old; update it."); return }
        when {
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                _state.value = if (Shizuku.shouldShowRequestPermissionRationale()) State.PermissionDenied else State.NeedsPermission
            _state.value !is State.Ready -> bind()
        }
    }

    /** Shows Shizuku's permission dialog. */
    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun bind() {
        val args = args ?: return
        _state.value = State.Binding
        try {
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            Log.e("Mirage", "bindUserService failed", t)
            _state.value = State.Failed(t.message ?: t.toString())
        }
    }

    /** Stops the service process (restores tablet settings via its destroy()). */
    fun shutdown() {
        val args = args ?: return
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        if (_state.value is State.Ready) _state.value = State.Binding
    }

    private fun versionOf(context: Context): Int = runCatching {
        (context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000).toInt()
    }.getOrDefault(1)
}
