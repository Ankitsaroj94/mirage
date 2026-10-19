package com.voxturr.mirage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.voxturr.mirage.receiver.Panel
import com.voxturr.mirage.receiver.ReceiverActivity
import com.voxturr.mirage.receiver.ReceiverHub
import com.voxturr.mirage.receiver.ReceiverService
import com.voxturr.mirage.receiver.ReceiverSettings

/** Home screen. The host role (agent 6) is reached by class name so this file does not depend on it. */
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Start regardless: without POST_NOTIFICATIONS the service still runs, just silently.
        ReceiverService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = HomeSettings(ReceiverSettings(this))
        val panel = Panel.probe(this, display)
        val sendScreen: (() -> Unit)? = if (isHostAvailable()) ({ startActivity(hostIntent()) }) else null

        setContent {
            MirageTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        val status by ReceiverHub.status.collectAsState()
                        HomeScreen(
                            status = status,
                            panelWidth = panel.pixelWidth,
                            panelHeight = panel.pixelHeight,
                            settings = settings,
                            onStartDisplay = ::startDisplay,
                            onStopDisplay = { ReceiverService.stop(this@MainActivity) },
                            onShowDisplay = {
                                startActivity(Intent(this@MainActivity, ReceiverActivity::class.java))
                            },
                            onSendScreen = sendScreen,
                        )
                    }
                }
            }
        }
    }

    private fun startDisplay() {
        val missing = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) ReceiverService.start(this) else permissions.launch(missing.toTypedArray())
    }

    private fun hostIntent() = Intent().setClassName(packageName, HOST_ACTIVITY)

    private fun isHostAvailable(): Boolean =
        packageManager.resolveActivity(hostIntent(), PackageManager.ResolveInfoFlags.of(0)) != null

    companion object {
        /** Agent 6's entry point; the card shows "Coming soon" until it is in the manifest. */
        const val HOST_ACTIVITY = "com.voxturr.mirage.host.HostActivity"
    }
}

@Composable
fun MirageTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
