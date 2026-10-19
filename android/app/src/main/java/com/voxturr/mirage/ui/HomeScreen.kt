package com.voxturr.mirage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.voxturr.mirage.R
import com.voxturr.mirage.receiver.ReceiverSettings
import com.voxturr.mirage.receiver.ReceiverStatus
import com.voxturr.mirage.receiver.ScalePreference

/** Compose-observable view of [ReceiverSettings]; every change is persisted immediately. */
class HomeSettings(private val store: ReceiverSettings) {
    var deviceName by mutableStateOf(store.deviceName); private set
    var pairingCode by mutableStateOf(store.pairingCode); private set
    var scale by mutableStateOf(store.scalePreference); private set
    var showStats by mutableStateOf(store.showStats); private set
    var ctrlAsCommand by mutableStateOf(store.ctrlAsCommand); private set

    fun updateDeviceName(v: String) { deviceName = v; store.deviceName = v }
    fun updatePairingCode(v: String) { val d = v.filter { it.isLetterOrDigit() }.take(12); pairingCode = d; store.pairingCode = d }
    fun updateScale(v: ScalePreference) { scale = v; store.scalePreference = v }
    fun updateShowStats(v: Boolean) { showStats = v; store.showStats = v }
    fun updateCtrlAsCommand(v: Boolean) { ctrlAsCommand = v; store.ctrlAsCommand = v }
}

/**
 * Home: "Be a display" and "Send my screen".
 * [onSendScreen] null = the host role is not in this build → the card shows "Coming soon".
 */
@Composable
fun HomeScreen(
    status: ReceiverStatus,
    panelWidth: Int,
    panelHeight: Int,
    settings: HomeSettings,
    onStartDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    onShowDisplay: () -> Unit,
    onSendScreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(24.dp)) {
        val wide = maxWidth > 900.dp
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Text("Mirage", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    DisplayCard(status, panelWidth, panelHeight, settings, onStartDisplay, onStopDisplay, onShowDisplay, Modifier.weight(1.4f))
                    SendCard(onSendScreen, Modifier.weight(1f))
                }
            } else {
                DisplayCard(status, panelWidth, panelHeight, settings, onStartDisplay, onStopDisplay, onShowDisplay, Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                SendCard(onSendScreen, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DisplayCard(
    status: ReceiverStatus,
    panelWidth: Int,
    panelHeight: Int,
    settings: HomeSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShow: () -> Unit,
    modifier: Modifier,
) {
    val running = status !is ReceiverStatus.Stopped && status !is ReceiverStatus.Failed
    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_display_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_display_body), style = MaterialTheme.typography.bodyMedium)

            StatusLine(status)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (running) {
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.home_stop)) }
                    if (status is ReceiverStatus.Connected) Button(onClick = onShow) { Text(stringResource(R.string.home_show_display)) }
                } else {
                    Button(onClick = onStart) { Text(stringResource(R.string.home_start)) }
                }
            }

            OutlinedTextField(
                value = settings.deviceName,
                onValueChange = settings::updateDeviceName,
                label = { Text(stringResource(R.string.home_device_name)) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.pairingCode,
                onValueChange = settings::updatePairingCode,
                label = { Text(stringResource(R.string.home_pairing_code)) },
                supportingText = { Text(stringResource(R.string.home_pairing_hint)) },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.home_scale), style = MaterialTheme.typography.titleMedium)
            ScalePreference.entries.forEach { pref ->
                val (w, h) = pref.looksLike(panelWidth, panelHeight)
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = settings.scale == pref, enabled = !running, role = Role.RadioButton,
                            onClick = { settings.updateScale(pref) })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.scale == pref, onClick = null, enabled = !running)
                    Spacer(Modifier.width(8.dp))
                    Text(pref.label, Modifier.weight(1f))
                    Text(stringResource(R.string.home_looks_like, w, h), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running) Text(stringResource(R.string.home_restart_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            SwitchRow(stringResource(R.string.home_show_stats), settings.showStats, settings::updateShowStats)
            SwitchRow(stringResource(R.string.home_ctrl_cmd), settings.ctrlAsCommand, settings::updateCtrlAsCommand)
        }
    }
}

@Composable
private fun StatusLine(status: ReceiverStatus) {
    val text = when (status) {
        ReceiverStatus.Stopped -> "Off"
        is ReceiverStatus.Waiting ->
            if (status.port == 0) status.message ?: "Starting…"
            else buildString {
                append("Waiting for a Mac — or connect manually to ")
                append(status.ip ?: "this tablet's IP").append(':').append(status.port)
                status.message?.let { append("\n").append(it) }
            }
        is ReceiverStatus.Connecting -> "Connecting to ${status.peer}…"
        is ReceiverStatus.Connected -> "Showing ${status.hostName}"
        is ReceiverStatus.Failed -> "Could not start: ${status.message}"
    }
    val color = when (status) {
        is ReceiverStatus.Connected -> MaterialTheme.colorScheme.primary
        is ReceiverStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SendCard(onSendScreen: (() -> Unit)?, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_send_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_send_body), style = MaterialTheme.typography.bodyMedium)
            if (onSendScreen != null) {
                Button(onClick = onSendScreen) { Text(stringResource(R.string.home_open)) }
            } else {
                OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.home_coming_soon)) }
            }
        }
    }
}
