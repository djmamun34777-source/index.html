package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.WalkieApplication

private const val SERVICE_TAG = "WalkieForegroundService"

/**
 * Always-Active Background Foreground Service for Mamun Walkie Talkie.
 *
 * - Keeps WebRTC mesh audio & Firestore listeners alive 24/7 while powered ON (even minimized/screen off).
 * - Holds a lightweight PARTIAL_WAKE_LOCK + Low-Latency WifiLock so incoming voice never drops.
 * - Complies with Android 14–16 FGS rules: runs as FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK in standby
 *   and promotes to MEDIA_PLAYBACK | MICROPHONE strictly while the user holds PTT with RECORD_AUDIO granted.
 * - Restarts automatically if killed by Android (START_STICKY) or after device reboot (WalkieBootReceiver).
 */
class WalkieForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireKeepAliveLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE_STATUS

        if (action == ACTION_POWER_OFF_FROM_NOTIFICATION) {
            (application as? WalkieApplication)?.walkieRuntime?.setPowerState(false)
            releaseKeepAliveLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP) {
            releaseKeepAliveLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // If restarted by Android (START_STICKY with null intent) or started on boot, bootstrap runtime!
        if (intent == null || action == ACTION_BOOT_AUTO_START) {
            (application as? WalkieApplication)?.walkieRuntime?.ensureStartedForCurrentAuthUser()
        }

        val statusTitle = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val statusBody = intent?.getStringExtra(EXTRA_BODY) ?: "Active in Background • Connected"
        val isTransmitting = intent?.getBooleanExtra(EXTRA_TRANSMITTING, false) ?: false

        val notification = buildNotification(statusTitle, statusBody, isTransmitting)
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isTransmitting && hasMicPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            Log.w(SERVICE_TAG, "Unable to promote foreground service: ${e.message}")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseKeepAliveLocks()
        super.onDestroy()
    }

    private fun acquireKeepAliveLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MamunWalkieTalkie::RadioKeepAliveWakeLock"
                ).apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L) // 6h safe ceiling, refreshed on updates
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "MamunWalkieTalkie::RadioWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseKeepAliveLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        body: String,
        transmitting: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val powerOffIntent = Intent(this, WalkieForegroundService::class.java).apply {
            action = ACTION_POWER_OFF_FROM_NOTIFICATION
        }
        val powerOffPending = PendingIntent.getService(
            this,
            1,
            powerOffIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (transmitting) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(0, "Power OFF", powerOffPending)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mamun_walkie_connection_channel"
        const val NOTIFICATION_ID = 4106
        const val ACTION_UPDATE_STATUS = "com.example.action.WALKIE_UPDATE_STATUS"
        const val ACTION_BOOT_AUTO_START = "com.example.action.WALKIE_BOOT_AUTO_START"
        const val ACTION_POWER_OFF_FROM_NOTIFICATION = "com.example.action.WALKIE_POWER_OFF"
        const val ACTION_STOP = "com.example.action.WALKIE_STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val EXTRA_TRANSMITTING = "extra_transmitting"

        fun updateStatus(
            context: Context,
            title: String,
            body: String,
            transmitting: Boolean
        ) {
            val intent = Intent(context.applicationContext, WalkieForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_TRANSMITTING, transmitting)
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.w(SERVICE_TAG, "Foreground service update skipped: ${e.message}")
            }
        }

        fun startOnBoot(context: Context) {
            val intent = Intent(context.applicationContext, WalkieForegroundService::class.java).apply {
                action = ACTION_BOOT_AUTO_START
                putExtra(EXTRA_TITLE, context.getString(R.string.app_name))
                putExtra(EXTRA_BODY, "Reconnecting in Background…")
                putExtra(EXTRA_TRANSMITTING, false)
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.w(SERVICE_TAG, "Boot auto-start skipped by OS: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, WalkieForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.applicationContext.stopService(intent) }
        }
    }
}
