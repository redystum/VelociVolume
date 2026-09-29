package com.redystum.velocivolume

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

class SpeedControlTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isCurrentlyRunning = SpeedState.isRunning || AppSettings(this).isServiceRunning

        if (isCurrentlyRunning) {
            SpeedMonitoringService.stopService(this)
            Toast.makeText(this, "VelociVolume Deactivated", Toast.LENGTH_SHORT).show()
            updateTileDisplay(false)
        } else {
            // Check location permission before activating
            val hasLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasLocation) {
                Toast.makeText(this, "Open VelociVolume to grant location permission", Toast.LENGTH_LONG).show()
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this,
                        0,
                        appIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(appIntent)
                }
                return
            }

            SpeedMonitoringService.startService(this)
            Toast.makeText(this, "VelociVolume Activated", Toast.LENGTH_SHORT).show()
            updateTileDisplay(true)
        }
    }

    private fun updateTileState() {
        val isRunning = SpeedState.isRunning || AppSettings(this).isServiceRunning
        updateTileDisplay(isRunning)
    }

    private fun updateTileDisplay(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (isActive) R.string.tile_active else R.string.tile_inactive)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_speedometer)
        tile.updateTile()
    }
}
