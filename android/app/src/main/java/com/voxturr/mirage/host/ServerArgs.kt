package com.voxturr.mirage.host

/**
 * Arguments of the shell-side host server — the same strings whether it was started by the Mac
 * over adb (`ServerMain <args>`) or by the tablet through Shizuku (`IPrivilegedService.startHost`).
 *
 * ```
 * --host <ip>            Mac's address (required). Over USB use 127.0.0.1 with `adb reverse`.
 * --port <n>             Mac's Mirage listener port (required)
 * --code <pairing code>  optional; enables encryption (spec §6)
 * --name <text>          host name shown on the Mac (default: the tablet's device name)
 * --bitrate <kbps>       target bitrate, default 30000
 * --min-bitrate <kbps>   AIMD floor, default 6000
 * --fps <n>              cap, default 60
 * --codec auto|hevc|h264 default auto (HEVC when the Mac and this encoder can do the size)
 * --no-input             ignore the Mac's mouse/keyboard
 * --no-adaptive          fixed bitrate
 * --cmd-as-meta          ⌘ → Android Meta instead of Ctrl
 * --desktop-settings     also flip the AOSP desktop-windowing settings (restored on exit)
 * ```
 */
data class ServerArgs(
    val host: String,
    val port: Int,
    val pairingCode: String? = null,
    val hostName: String? = null,
    val options: HostSession.Options = HostSession.Options(),
) {
    companion object {
        /** "host:port" (IPv6 as [addr]:port) from the manual-address field; null if malformed. */
        fun parseAddress(text: String): Pair<String, Int>? {
            val t = text.trim()
            val i = t.lastIndexOf(':')
            if (i <= 0) return null
            val port = t.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = t.substring(0, i).removePrefix("[").removeSuffix("]")
            return if (host.isBlank()) null else host to port
        }

        /** @throws IllegalArgumentException with a message fit for an `ERR` line. */
        fun parse(args: Array<String>): ServerArgs {
            var host: String? = null
            var port: Int? = null
            var code: String? = null
            var name: String? = null
            var o = HostSession.Options()
            var i = 0
            fun value(flag: String): String {
                require(i + 1 < args.size) { "missing value for $flag" }
                return args[++i]
            }
            fun int(flag: String): Int = value(flag).toIntOrNull() ?: throw IllegalArgumentException("$flag needs a number")
            while (i < args.size) {
                when (val a = args[i]) {
                    "--host" -> host = value(a)
                    "--port" -> port = int(a)
                    "--code" -> code = value(a).ifBlank { null }
                    "--name" -> name = value(a)
                    "--bitrate" -> o = o.copy(targetBitrateKbps = int(a))
                    "--min-bitrate" -> o = o.copy(minimumBitrateKbps = int(a))
                    "--fps" -> o = o.copy(maxFps = int(a))
                    "--codec" -> o = when (value(a)) {
                        "auto" -> o.copy(preferHevc = true, forceH264 = false)
                        "hevc" -> o.copy(preferHevc = true, forceH264 = false)
                        "h264" -> o.copy(preferHevc = false, forceH264 = true)
                        else -> throw IllegalArgumentException("--codec must be auto, hevc or h264")
                    }
                    "--no-input" -> o = o.copy(acceptRemoteInput = false)
                    "--no-adaptive" -> o = o.copy(adaptiveBitrate = false)
                    "--cmd-as-meta" -> o = o.copy(commandAsCtrl = false)
                    "--desktop-settings" -> o = o.copy(desktopModeSettings = true)
                    else -> throw IllegalArgumentException("unknown argument $a")
                }
                i++
            }
            requireNotNull(host) { "--host is required" }
            val p = requireNotNull(port) { "--port is required" }
            require(p in 1..65535) { "--port out of range" }
            require(o.minimumBitrateKbps in 500..o.targetBitrateKbps) { "--min-bitrate must be between 500 and --bitrate" }
            require(o.maxFps in 30..120) { "--fps must be 30…120" }
            return ServerArgs(host, p, code, name, o)
        }
    }
}
