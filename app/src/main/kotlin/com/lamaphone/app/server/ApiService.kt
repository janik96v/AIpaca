package com.lamaphone.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lamaphone.app.EngineState
import com.lamaphone.app.server.security.AuthorizedKeysStore
import com.lamaphone.app.server.security.TlsManager
import java.net.NetworkInterface

class ApiService : Service() {

    companion object {
        const val CHANNEL_ID = "lamaphone_server"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.lamaphone.app.ACTION_STOP_SERVER"

        private const val TAG = "ApiService"
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "ApiService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting API server on port ${ApiServer.port}")

        val ip = getLocalIpAddress()
        startForeground(NOTIFICATION_ID, buildNotification(ip))

        if (!ApiServer.isRunning()) {
            // Pass local IP so TlsManager can add a SAN — enables proper TLS hostname verification
            val tlsConfig = TlsManager.getOrCreate(applicationContext, localIp = ip)
            val authorizedKeys = AuthorizedKeysStore(applicationContext)
            // Bind only to the local WiFi/eth interface to avoid exposing on hotspot or VPN
            ApiServer.start(EngineState, tlsConfig, authorizedKeys, bindAddress = ip)
        }

        // Keep ServerManager state in sync
        ServerManager.onServiceStarted(ip)

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ApiService destroying — stopping server")
        ApiServer.stop()
        ServerManager.onServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LamaPhone API Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while the OpenAI-compatible API server is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(ip: String): Notification {
        val stopIntent = Intent(this, ApiService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LamaPhone Server")
            .setContentText("https://$ip:${ApiServer.port}")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Server",
                stopPendingIntent
            )
            .build()
    }

    // -------------------------------------------------------------------------
    // Network helpers
    // -------------------------------------------------------------------------

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (iface in interfaces.asSequence()) {
                // Prefer wlan interfaces
                if (!iface.name.startsWith("wlan") && !iface.name.startsWith("eth")) continue
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr.isLoopbackAddress) continue
                    val hostAddr = addr.hostAddress ?: continue
                    // IPv4 only
                    if (!hostAddr.contains(':')) return hostAddr
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine local IP: ${e.message}")
        }
        return "0.0.0.0"
    }
}
