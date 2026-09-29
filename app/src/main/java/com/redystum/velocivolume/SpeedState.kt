package com.redystum.velocivolume

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object SpeedState {
    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var currentSpeedKmh: Float = 0f
        private set

    @Volatile
    var currentVolumePercent: Int = 0
        private set

    @Volatile
    var currentVolumeLevel: Int = 0
        private set

    @Volatile
    var maxVolumeLevel: Int = 15
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    interface Listener {
        fun onSpeedStateUpdated(
            isRunning: Boolean,
            speedKmh: Float,
            volumePercent: Int,
            volumeLevel: Int,
            maxVolumeLevel: Int
        )
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onSpeedStateUpdated(
            isRunning,
            currentSpeedKmh,
            currentVolumePercent,
            currentVolumeLevel,
            maxVolumeLevel
        )
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun updateServiceStatus(running: Boolean) {
        isRunning = running
        if (!running) {
            currentSpeedKmh = 0f
        }
        dispatch()
    }

    fun updateMetrics(speedKmh: Float, volumePercent: Int, volumeLevel: Int, maxVolume: Int) {
        currentSpeedKmh = speedKmh
        currentVolumePercent = volumePercent
        currentVolumeLevel = volumeLevel
        maxVolumeLevel = maxVolume
        dispatch()
    }

    private fun dispatch() {
        mainHandler.post {
            for (listener in listeners) {
                listener.onSpeedStateUpdated(
                    isRunning,
                    currentSpeedKmh,
                    currentVolumePercent,
                    currentVolumeLevel,
                    maxVolumeLevel
                )
            }
        }
    }
}
