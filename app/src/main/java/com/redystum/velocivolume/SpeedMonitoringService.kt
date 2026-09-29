package com.redystum.velocivolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.roundToInt

class SpeedMonitoringService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var audioManager: AudioManager
    private lateinit var appSettings: AppSettings
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var smoothedSpeedKmh: Float = 0f
    private var lastReportedVolume: Int = -1
    private var wasAutoPaused: Boolean = false
    private var lastNotificationUpdateTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SpeedMonitoringService created")

        appSettings = AppSettings(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startAsForegroundService()

        appSettings.isServiceRunning = true
        appSettings.registerListener(this)

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VelociVolume::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 hours timeout for safety
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire wake lock: ${e.message}")
        }

        SpeedState.updateServiceStatus(true)

        setupLocationCallback()
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "Received STOP command from notification")
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val lastLocation: Location = locationResult.lastLocation ?: return
                // speed is in m/s, convert to km/h
                val rawSpeedKmh = (lastLocation.speed * 3.6f).coerceAtLeast(0f)

                val speedKmh = if (appSettings.smoothGps) {
                    val alpha = 0.35f
                    smoothedSpeedKmh = (alpha * rawSpeedKmh) + ((1f - alpha) * smoothedSpeedKmh)
                    smoothedSpeedKmh
                } else {
                    smoothedSpeedKmh = rawSpeedKmh
                    rawSpeedKmh
                }

                processSpeedAndAdjustVolume(speedKmh)
            }
        }
    }

    private fun processSpeedAndAdjustVolume(speedKmh: Float) {
        val minSpeed = appSettings.minSpeedKmh
        val maxSpeed = appSettings.maxSpeedKmh
        val minVolPercent = appSettings.minVolumePercent
        val maxVolPercent = appSettings.maxVolumePercent

        val speedRange = (maxSpeed - minSpeed).coerceAtLeast(1f)
        val clampedSpeed = speedKmh.coerceIn(minSpeed, maxSpeed)
        val ratio = (clampedSpeed - minSpeed) / speedRange
        val targetPercent = (minVolPercent + (ratio * (maxVolPercent - minVolPercent))).roundToInt().coerceIn(0, 100)

        val maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetSystemVolume = ((targetPercent / 100f) * maxSystemVolume).roundToInt().coerceIn(0, maxSystemVolume)

        if (targetSystemVolume != lastReportedVolume) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSystemVolume, 0)
                lastReportedVolume = targetSystemVolume
            } catch (e: Exception) {
                Log.e(TAG, "Error setting stream volume: ${e.message}")
            }
        }

        // Handle auto-pause when stopped
        if (appSettings.autoPauseMedia) {
            if (speedKmh < 1.0f && !wasAutoPaused) {
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
                wasAutoPaused = true
            } else if (speedKmh >= 2.5f && wasAutoPaused) {
                sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                wasAutoPaused = false
            }
        }

        // Update real-time state for MainActivity and tile
        SpeedState.updateMetrics(speedKmh, targetPercent, targetSystemVolume, maxSystemVolume)

        // Throttle notification text update to once every 1.5 seconds to reduce system overhead
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime >= 1500L) {
            lastNotificationUpdateTime = now
            updateNotification(speedKmh, targetPercent)
        }
    }

    private fun sendMediaButtonEvent(keyCode: Int) {
        try {
            val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(eventDown)
            audioManager.dispatchMediaKeyEvent(eventUp)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching media key: ${e.message}")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permissions missing in service")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        try {
            if (::fusedLocationClient.isInitialized && ::locationCallback.isInitialized) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(speedKmh: Float, volumePercent: Int): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SpeedMonitoringService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val displaySpeed = appSettings.kmhToDisplay(speedKmh)
        val unitLabel = appSettings.speedUnit.label
        val contentText = getString(R.string.notification_speed_volume, displaySpeed, unitLabel, volumePercent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_speedometer)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_stop_action),
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startAsForegroundService() {
        val initialNotification = buildNotification(0f, appSettings.minVolumePercent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun updateNotification(speedKmh: Float, volumePercent: Int) {
        val notification = buildNotification(speedKmh, volumePercent)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        Log.d(TAG, "Settings changed, re-evaluating volume curve")
        processSpeedAndAdjustVolume(smoothedSpeedKmh)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SpeedMonitoringService destroyed")

        if (::appSettings.isInitialized) {
            appSettings.unregisterListener(this)
            appSettings.isServiceRunning = false
        }
        SpeedState.updateServiceStatus(false)

        stopLocationUpdates()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakeLock: ${e.message}")
        }

        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SpeedMonitoringService"
        const val CHANNEL_ID = "veloci_speed_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "com.redystum.velocivolume.ACTION_STOP_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, SpeedMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SpeedMonitoringService::class.java)
            context.stopService(intent)
        }
    }
}
