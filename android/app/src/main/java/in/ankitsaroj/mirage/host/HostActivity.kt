package `in`.ankitsaroj.mirage.host

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.ankitsaroj.mirage.core.DiscoveredDisplay
import `in`.ankitsaroj.mirage.core.DisplayBrowser
import kotlinx.coroutines.delay

/**
 * "Send my screen" — the Mac becomes an extended display for this tablet.
 *
 * Two ways in, because the privileged half must run as the shell user:
 *  1. **From the Mac over adb** (primary): the Mac app starts `ServerMain` with
 *     `adb shell app_process`; nothing to do here. We only notice it — its virtual display
 *     is public, so it shows up in DisplayManager — and say so.
 *  2. **Shizuku** (optional, tablet-only): the same server runs as a Shizuku UserService; this
 *     screen picks a Mac (discovered or typed) and starts/stops it.
 */
class HostActivity : ComponentActivity() {

    private val browser by lazy { DisplayBrowser(this) }
    private val macs = mutableStateListOf<DiscoveredDisplay>()
    private var adbDisplay by mutableStateOf<String?>(null)
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refreshAdbDisplay()
        override fun onDisplayRemoved(id: Int) = refreshAdbDisplay()
        override fun onDisplayChanged(id: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuConnector.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { HostScreen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ShizukuConnector.refresh()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshAdbDisplay()
        browser.onUpdate = { list -> macs.clear(); macs += list.filter { it.platform != "android" } }
        // Discovery lives in core; never let it take this screen down.
        runCatching { browser.start() }
    }

    override fun onStop() {
        runCatching { browser.stop() }
        displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    /** A public display named "… (Mirage)" means a host server is running (adb or Shizuku). */
    private fun refreshAdbDisplay() {
        adbDisplay = displayManager.displays.firstOrNull { it.name.endsWith("(Mirage)") }
            ?.let { d: Display -> "${d.name} — display ${d.displayId}, ${d.mode.physicalWidth}×${d.mode.physicalHeight}" }
    }

    @Composable
    private fun HostScreen() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Send my screen", style = MaterialTheme.typography.headlineMedium)
            Text("Your Mac becomes a second Android desktop: apps open as windows on it, driven with the Mac's mouse and keyboard.")
            AdbCard()
            ShizukuCard()
        }
    }

    @Composable
    private fun AdbCard() = Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start it from the Mac (recommended)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connect this tablet to the Mac with USB (or pair Wireless debugging), allow USB debugging, " +
                    "then choose this tablet in Mirage on the Mac. Nothing else is needed here.",
            )
            Text(
                adbDisplay?.let { "Active: $it" } ?: "Not running.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (adbDisplay != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun ShizukuCard() = Card(Modifier.fillMaxWidth()) {
        val shizuku by ShizukuConnector.state.collectAsState()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Or start it from this tablet with Shizuku (optional)", style = MaterialTheme.typography.titleMedium)
            when (val s = shizuku) {
                ShizukuConnector.State.NotRunning -> {
                    Text("Shizuku isn't running. Install Shizuku and start it (via Wireless debugging) after each reboot.")
                    OutlinedButton(onClick = { ShizukuConnector.refresh() }) { Text("Check again") }
                }
                ShizukuConnector.State.NeedsPermission, ShizukuConnector.State.PermissionDenied -> {
                    Text(if (s == ShizukuConnector.State.PermissionDenied) "Mirage was denied Shizuku access. Allow it in Shizuku." else "Mirage needs Shizuku access to create the display.")
                    Button(onClick = { ShizukuConnector.requestPermission() }) { Text("Grant access") }
                }
                ShizukuConnector.State.Binding -> Text("Starting the privileged service…")
                is ShizukuConnector.State.Failed -> {
                    Text("Shizuku error: ${s.message}")
                    OutlinedButton(onClick = { ShizukuConnector.refresh() }) { Text("Retry") }
                }
                is ShizukuConnector.State.Ready -> ShizukuHost()
            }
        }
    }

    @Composable
    private fun ShizukuHost() {
        var status by remember { mutableStateOf("") }
        var stats by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val service = ShizukuConnector.service
                status = runCatching { service?.status() }.getOrNull() ?: ""
                stats = runCatching { service?.stats() }.getOrNull() ?: ""
                delay(500)
            }
        }
        val active = status.startsWith("OK") || status.startsWith("CONNECT")
        fun start(host: String, port: Int) {
            val args = buildList {
                add("--host"); add(host); add("--port"); add(port.toString())
                if (code.isNotBlank()) { add("--code"); add(code.trim()) }
            }.toTypedArray()
            status = runCatching { ShizukuConnector.service?.startHost(args) }.getOrNull() ?: "ERR service unavailable"
        }

        Text("Status: ${describe(status)}")
        if (stats.isNotEmpty()) Text(stats, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (active) {
            Button(onClick = { runCatching { ShizukuConnector.service?.stopHost() } }) { Text("Disconnect") }
            return
        }

        Text("Macs showing \"Be a display\"", style = MaterialTheme.typography.titleSmall)
        if (macs.isEmpty()) Text("None found yet. Open Mirage on the Mac and turn on \"Be a display\", or type its address below.")
        for (mac in macs) {
            Row(
                Modifier.fillMaxWidth().clickable { start(mac.host.hostAddress ?: return@clickable, mac.port) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(mac.name)
                    Text("${mac.host.hostAddress}:${mac.port} · ${mac.resolutionSummary}" + if (mac.requiresPairingCode) " · code required" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Connect", color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedTextField(code, { code = it }, label = { Text("Pairing code (if the Mac uses one)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(address, { address = it }, label = { Text("Mac address, e.g. 192.168.1.20:7250") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            val parsed = ServerArgs.parseAddress(address)
            Button(enabled = parsed != null, onClick = { parsed?.let { start(it.first, it.second) } }) { Text("Connect") }
        }
    }

    private fun describe(status: String): String = when {
        status.isEmpty() || status == "IDLE" -> "idle"
        status.startsWith("CONNECTING") -> "connecting to ${status.substringAfter(' ')}…"
        status == "CONNECTED" -> "connected, waiting for the Mac…"
        status.startsWith("OK") -> status.split(' ').let { "streaming ${it.getOrElse(2) { "" }} ${it.getOrElse(3) { "" }} @ ${it.getOrElse(4) { "" }} fps (display ${it[1]})" }
        status.startsWith("ERR") -> "failed: ${status.removePrefix("ERR ")}"
        status == "BYE" -> "ended"
        else -> status
    }
}
