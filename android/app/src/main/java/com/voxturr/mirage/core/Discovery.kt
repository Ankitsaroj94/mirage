package com.voxturr.mirage.core

import android.content.Context
import java.net.InetAddress

// DNS-SD "_mirage._tcp" via NsdManager — spec/PROTOCOL.md §1.

const val SERVICE_TYPE = "_mirage._tcp"

data class DiscoveredDisplay(
    val name: String,
    val host: InetAddress,
    val port: Int,
    val txt: Map<String, String>,
) {
    val platform: String get() = txt["os"] ?: "macos"
    val requiresPairingCode: Boolean get() = txt["lock"] == "1"
    val resolutionSummary: String get() = "${txt["px"] ?: "?"} @ ${txt["hz"] ?: "60"}Hz"
}

/** Advertises this device as an available display. */
class DisplayAdvertiser(context: Context) {
    fun start(displayName: String, port: Int, txt: Map<String, String>): Unit = TODO("agent 4")
    fun stop(): Unit = TODO("agent 4")
}

/** Finds displays (Macs or tablets) offering themselves. Callback on the main thread. */
class DisplayBrowser(context: Context) {
    var onUpdate: ((List<DiscoveredDisplay>) -> Unit)? = null
    fun start(): Unit = TODO("agent 4")
    fun stop(): Unit = TODO("agent 4")
}
