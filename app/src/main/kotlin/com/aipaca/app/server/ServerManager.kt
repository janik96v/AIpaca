package com.aipaca.app.server

import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ServerManager"

/**
 * Singleton that exposes observable server state (running flag + URL) and
 * provides start/stop helpers that delegate to [ApiService].
 *
 * The actual running state is kept in sync by [ApiService] via
 * [onServiceStarted] / [onServiceStopped].
 */
object ServerManager {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start(context: Context) {
        Log.i(TAG, "start() called")
        val intent = Intent(context, ApiService::class.java)
        context.startForegroundService(intent)
        // Optimistic update; ApiService will confirm via onServiceStarted()
        _isRunning.value = true
        _serverUrl.value = buildUrl(getLocalIpAddress())
    }

    fun stop(context: Context) {
        Log.i(TAG, "stop() called")
        val intent = Intent(context, ApiService::class.java)
        context.stopService(intent)
        _isRunning.value = false
        _serverUrl.value = null
    }

    /** Called by [ApiService.onStartCommand] once the server is up. */
    fun onServiceStarted(ip: String) {
        _isRunning.value = true
        _serverUrl.value = buildUrl(ip)
        Log.i(TAG, "Server started at ${_serverUrl.value}")
    }

    /** Called by [ApiService.onDestroy] when the service is going down. */
    fun onServiceStopped() {
        _isRunning.value = false
        _serverUrl.value = null
        Log.i(TAG, "Server stopped")
    }

    // -------------------------------------------------------------------------
    // Network helper
    // -------------------------------------------------------------------------

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (iface in interfaces.asSequence()) {
                if (!iface.name.startsWith("wlan") && !iface.name.startsWith("eth")) continue
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr.isLoopbackAddress) continue
                    val hostAddr = addr.hostAddress ?: continue
                    if (!hostAddr.contains(':')) return hostAddr  // IPv4 only
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IP: ${e.message}")
        }
        return "0.0.0.0"
    }

    private fun buildUrl(ip: String) = "https://$ip:${ApiServer.port}"
}
