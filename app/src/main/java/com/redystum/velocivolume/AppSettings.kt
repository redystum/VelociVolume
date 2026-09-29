package com.redystum.velocivolume

import android.content.Context
import android.content.SharedPreferences

enum class SpeedUnit(val label: String, val maxSliderSpeed: Float) {
    KMH("km/h", 200f),
    MPH("mph", 120f);

    companion object {
        fun fromString(value: String): SpeedUnit {
            return if (value.equals("mph", ignoreCase = true)) MPH else KMH
        }
    }
}

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var speedUnit: SpeedUnit
        get() = SpeedUnit.fromString(prefs.getString(KEY_SPEED_UNIT, "kmh") ?: "kmh")
        set(value) {
            prefs.edit().putString(KEY_SPEED_UNIT, value.name.lowercase()).apply()
        }

    /**
     * Stored in km/h internally.
     */
    var minSpeedKmh: Float
        get() = prefs.getFloat(KEY_MIN_SPEED, 0f)
        set(value) {
            prefs.edit().putFloat(KEY_MIN_SPEED, value.coerceAtLeast(0f)).apply()
        }

    /**
     * Stored in km/h internally.
     */
    var maxSpeedKmh: Float
        get() = prefs.getFloat(KEY_MAX_SPEED, 100f)
        set(value) {
            prefs.edit().putFloat(KEY_MAX_SPEED, value.coerceAtLeast(10f)).apply()
        }

    var minVolumePercent: Int
        get() = prefs.getInt(KEY_MIN_VOLUME, 20)
        set(value) {
            prefs.edit().putInt(KEY_MIN_VOLUME, value.coerceIn(0, 100)).apply()
        }

    var maxVolumePercent: Int
        get() = prefs.getInt(KEY_MAX_VOLUME, 90)
        set(value) {
            prefs.edit().putInt(KEY_MAX_VOLUME, value.coerceIn(0, 100)).apply()
        }

    var autoPauseMedia: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_PAUSE, value).apply()
        }

    var smoothGps: Boolean
        get() = prefs.getBoolean(KEY_SMOOTH_GPS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SMOOTH_GPS, value).apply()
        }

    var isServiceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()
        }

    fun kmhToDisplay(kmh: Float): Float {
        return if (speedUnit == SpeedUnit.MPH) kmh * KMH_TO_MPH else kmh
    }

    fun displayToKmh(displaySpeed: Float): Float {
        return if (speedUnit == SpeedUnit.MPH) displaySpeed * MPH_TO_KMH else displaySpeed
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "veloci_volume_prefs"
        const val KEY_SPEED_UNIT = "key_speed_unit"
        const val KEY_MIN_SPEED = "key_min_speed"
        const val KEY_MAX_SPEED = "key_max_speed"
        const val KEY_MIN_VOLUME = "key_min_volume"
        const val KEY_MAX_VOLUME = "key_max_volume"
        const val KEY_AUTO_PAUSE = "key_auto_pause"
        const val KEY_SMOOTH_GPS = "key_smooth_gps"
        const val KEY_SERVICE_RUNNING = "key_service_running"

        const val KMH_TO_MPH = 0.621371f
        const val MPH_TO_KMH = 1.609344f
    }
}
