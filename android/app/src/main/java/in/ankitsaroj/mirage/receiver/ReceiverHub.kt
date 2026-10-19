package `in`.ankitsaroj.mirage.receiver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the "Be a display" role is doing, for the home screen and the notification. */
sealed interface ReceiverStatus {
    data object Stopped : ReceiverStatus
    /** Listening and advertised; [ip]:[port] is shown for manual connection. */
    data class Waiting(val ip: String?, val port: Int, val message: String? = null) : ReceiverStatus
    data class Connecting(val ip: String?, val port: Int, val peer: String) : ReceiverStatus
    data class Connected(val ip: String?, val port: Int, val hostName: String) : ReceiverStatus
    data class Failed(val message: String) : ReceiverStatus
}

/** Process-wide rendezvous between [ReceiverService], [ReceiverActivity] and the UI. */
object ReceiverHub {
    private val _status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)
    val status: StateFlow<ReceiverStatus> get() = _status

    /** The live session, if a host is connected. */
    @Volatile var session: ReceiverSession? = null
        internal set

    internal fun publish(s: ReceiverStatus) { _status.value = s }
}
