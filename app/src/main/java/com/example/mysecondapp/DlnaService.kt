package com.example.mysecondapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mysecondapp.dlna_lib.android.AndroidDlnaPlatform
import com.example.mysecondapp.dlna_lib.api.DlnaConfig
import com.example.mysecondapp.dlna_lib.api.DlnaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class DlnaService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Both locks are required for background operation.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "DLNA_SERVER_CHANNEL"
        private const val TAG = "DlnaService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        acquireLocks()
        createNotificationChannel()
    }

    private fun acquireLocks() {
        try {
            // 1. CPU WakeLock (Keeps the processor from sleeping)
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DlnaApp:ServiceWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()

            // 2. WiFi Lock (Keeps the WiFi radio active)
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DlnaApp:ServiceWifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()

            Log.i(TAG, "WakeLock and WifiLock acquired successfully.")

        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to acquire locks. Ensure WAKE_LOCK permission is in Manifest.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released.")
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDlnaSystem()
            ACTION_STOP -> stopDlnaSystem()
        }
        return START_STICKY
    }

    private fun startDlnaSystem() {
        try {
            val notification = createNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            serviceScope.launch {
                try {
                    if (!DlnaManager.isInitialized()) {
                        initializeDlnaManager()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Failed to initialize DlnaManager", e)
                    stopDlnaSystem()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Foreground Service", e)
            stopSelf()
        }
    }

    private fun initializeDlnaManager() {
        Log.d(TAG, "Initializing DLNA Manager...")
        val prefs = getSharedPreferences("dlna_prefs", Context.MODE_PRIVATE)
        val savedFolders = prefs.getStringSet("shared_folders", emptySet()) ?: emptySet()
        var serverUdn = prefs.getString("server_udn", null) ?: UUID.randomUUID().toString()
        prefs.edit().putString("server_udn", serverUdn).apply()

        val myContentProvider = MediaStoreContentProvider(this)
        myContentProvider.setAllowedFolders(savedFolders)

        val config = DlnaConfig(
            enableMediaServer = true,
            serverName = "Android (${Build.MODEL})",
            serverUdn = serverUdn,
            contentProvider = myContentProvider
        )

        val platform = AndroidDlnaPlatform(this)
        DlnaManager.start(config, platform)
        Log.i(TAG, "DLNA Manager Initialized and Started.")
    }

    private fun stopDlnaSystem() {
        try {
            DlnaManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        DlnaManager.stop()
        serviceScope.cancel()
        releaseLocks()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DlnaService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DLNA Server Active")
            .setContentText("Serving media in background")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DLNA Server Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}