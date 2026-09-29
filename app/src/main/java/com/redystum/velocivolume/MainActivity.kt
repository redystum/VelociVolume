package com.redystum.velocivolume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.redystum.velocivolume.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SpeedState.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettings: AppSettings
    private lateinit var audioManager: AudioManager
    private var isUpdatingSwitchProgrammatically: Boolean = false
    private var pendingStartMonitoring: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineLocationGranted || coarseLocationGranted) {
                updatePermissionBanner()
                if (pendingStartMonitoring || binding.masterSwitch.isChecked) {
                    pendingStartMonitoring = false
                    startMonitoring()
                }
            } else {
                pendingStartMonitoring = false
                Toast.makeText(this, "Location permission is required for speed tracking", Toast.LENGTH_SHORT).show()
                isUpdatingSwitchProgrammatically = true
                binding.masterSwitch.isChecked = false
                isUpdatingSwitchProgrammatically = false
                updatePermissionBanner()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupViews()
        updatePermissionBanner()
    }

    override fun onStart() {
        super.onStart()
        SpeedState.addListener(this)
        updatePermissionBanner()
    }

    override fun onStop() {
        super.onStop()
        SpeedState.removeListener(this)
    }

    private fun setupViews() {
        // Initialize Master Switch
        val isRunning = SpeedState.isRunning || appSettings.isServiceRunning
        isUpdatingSwitchProgrammatically = true
        binding.masterSwitch.isChecked = isRunning
        isUpdatingSwitchProgrammatically = false
        updateStatusUi(isRunning)

        binding.masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            if (isChecked) {
                if (hasRequiredPermissions()) {
                    pendingStartMonitoring = false
                    startMonitoring()
                } else {
                    pendingStartMonitoring = true
                    isUpdatingSwitchProgrammatically = true
                    binding.masterSwitch.isChecked = false
                    isUpdatingSwitchProgrammatically = false
                    requestRequiredPermissions()
                }
            } else {
                pendingStartMonitoring = false
                stopMonitoring()
            }
        }

        binding.grantPermissionsButton.setOnClickListener {
            requestRequiredPermissions()
        }

        // Speed Unit Toggle Button
        updateUnitUi()
        binding.speedUnitToggleBtn.setOnClickListener {
            val newUnit = if (appSettings.speedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
            appSettings.speedUnit = newUnit
            updateUnitUi()
            setupSpeedSliders()
        }

        setupSpeedSliders()
        setupVolumeSliders()
        setupSmartSwitches()
    }

    private fun updateUnitUi() {
        val unit = appSettings.speedUnit
        binding.speedUnitToggleBtn.text = unit.label
        binding.liveSpeedUnitText.text = unit.label
    }

    private fun setupSpeedSliders() {
        val unit = appSettings.speedUnit
        val maxSlider = unit.maxSliderSpeed // 200f for KM/H, 120f for MPH
        val step = 5f

        val currentMinDisplay = appSettings.kmhToDisplay(appSettings.minSpeedKmh).coerceIn(0f, maxSlider - step)
        val currentMaxDisplay = appSettings.kmhToDisplay(appSettings.maxSpeedKmh).coerceIn(step, maxSlider)

        // Clear listeners first to avoid triggering while reconfiguring
        binding.minSpeedSlider.clearOnChangeListeners()
        binding.maxSpeedSlider.clearOnChangeListeners()

        // 1. Reconfigure minSpeedSlider: reset stepSize first to avoid BaseSlider validation error
        binding.minSpeedSlider.stepSize = 0f
        binding.minSpeedSlider.valueFrom = 0f
        binding.minSpeedSlider.valueTo = maxSlider
        val safeMinVal = ((currentMinDisplay / step).roundToInt() * step).coerceIn(0f, maxSlider - step).toFloat()
        binding.minSpeedSlider.value = safeMinVal
        binding.minSpeedSlider.stepSize = step

        // 2. Reconfigure maxSpeedSlider: reset stepSize first to avoid BaseSlider validation error
        binding.maxSpeedSlider.stepSize = 0f
        binding.maxSpeedSlider.valueFrom = 0f
        binding.maxSpeedSlider.valueTo = maxSlider
        val safeMaxVal = ((currentMaxDisplay / step).roundToInt() * step).coerceIn(safeMinVal + step, maxSlider).toFloat()
        binding.maxSpeedSlider.value = safeMaxVal
        binding.maxSpeedSlider.stepSize = step

        // Re-attach listeners
        binding.minSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                if (value >= binding.maxSpeedSlider.value) {
                    val newMax = (value + step).coerceAtMost(maxSlider)
                    binding.maxSpeedSlider.value = newMax
                    appSettings.maxSpeedKmh = appSettings.displayToKmh(newMax)
                    updateMaxSpeedBadge(newMax)
                }
                appSettings.minSpeedKmh = appSettings.displayToKmh(value)
            }
            updateMinSpeedBadge(value)
        }

        binding.maxSpeedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                if (value <= binding.minSpeedSlider.value) {
                    val newMin = (value - step).coerceAtLeast(0f)
                    binding.minSpeedSlider.value = newMin
                    appSettings.minSpeedKmh = appSettings.displayToKmh(newMin)
                    updateMinSpeedBadge(newMin)
                }
                appSettings.maxSpeedKmh = appSettings.displayToKmh(value)
            }
            updateMaxSpeedBadge(value)
        }

        updateMinSpeedBadge(binding.minSpeedSlider.value)
        updateMaxSpeedBadge(binding.maxSpeedSlider.value)
    }

    private fun updateMinSpeedBadge(value: Float) {
        binding.minSpeedBadge.text = String.format("%.0f %s", value, appSettings.speedUnit.label)
    }

    private fun updateMaxSpeedBadge(value: Float) {
        binding.maxSpeedBadge.text = String.format("%.0f %s", value, appSettings.speedUnit.label)
    }

    private fun setupVolumeSliders() {
        val minVol = appSettings.minVolumePercent.toFloat()
        val maxVol = appSettings.maxVolumePercent.toFloat()
        val step = 5f

        binding.minVolumeSlider.clearOnChangeListeners()
        binding.maxVolumeSlider.clearOnChangeListeners()

        binding.minVolumeSlider.stepSize = 0f
        binding.minVolumeSlider.valueFrom = 0f
        binding.minVolumeSlider.valueTo = 100f
        val safeMinVol = ((minVol / step).roundToInt() * step).coerceIn(0f, 95f).toFloat()
        binding.minVolumeSlider.value = safeMinVol
        binding.minVolumeSlider.stepSize = step

        binding.maxVolumeSlider.stepSize = 0f
        binding.maxVolumeSlider.valueFrom = 0f
        binding.maxVolumeSlider.valueTo = 100f
        val safeMaxVol = ((maxVol / step).roundToInt() * step).coerceIn(safeMinVol + step, 100f).toFloat()
        binding.maxVolumeSlider.value = safeMaxVol
        binding.maxVolumeSlider.stepSize = step

        binding.minVolumeSlider.addOnChangeListener { _, value, fromUser ->
            val intVal = value.roundToInt()
            if (fromUser) {
                if (intVal >= binding.maxVolumeSlider.value.toInt()) {
                    val newMax = (intVal + 5).coerceAtMost(100).toFloat()
                    binding.maxVolumeSlider.value = newMax
                    appSettings.maxVolumePercent = newMax.toInt()
                    updateMaxVolumeBadge(newMax.toInt())
                }
                appSettings.minVolumePercent = intVal
            }
            updateMinVolumeBadge(intVal)
        }

        binding.maxVolumeSlider.addOnChangeListener { _, value, fromUser ->
            val intVal = value.roundToInt()
            if (fromUser) {
                if (intVal <= binding.minVolumeSlider.value.toInt()) {
                    val newMin = (intVal - 5).coerceAtLeast(0).toFloat()
                    binding.minVolumeSlider.value = newMin
                    appSettings.minVolumePercent = newMin.toInt()
                    updateMinVolumeBadge(newMin.toInt())
                }
                appSettings.maxVolumePercent = intVal
            }
            updateMaxVolumeBadge(intVal)
        }

        updateMinVolumeBadge(appSettings.minVolumePercent)
        updateMaxVolumeBadge(appSettings.maxVolumePercent)
    }

    private fun updateMinVolumeBadge(value: Int) {
        binding.minVolumeBadge.text = "$value%"
    }

    private fun updateMaxVolumeBadge(value: Int) {
        binding.maxVolumeBadge.text = "$value%"
    }

    private fun setupSmartSwitches() {
        binding.autoPauseSwitch.isChecked = appSettings.autoPauseMedia
        binding.autoPauseSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.autoPauseMedia = isChecked
        }

        binding.smoothingSwitch.isChecked = appSettings.smoothGps
        binding.smoothingSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.smoothGps = isChecked
        }
    }

    private fun startMonitoring() {
        SpeedMonitoringService.startService(this)
        appSettings.isServiceRunning = true
        updateStatusUi(true)
    }

    private fun stopMonitoring() {
        SpeedMonitoringService.stopService(this)
        appSettings.isServiceRunning = false
        updateStatusUi(false)
        binding.liveSpeedValueText.text = "0.0"
    }

    private fun updateStatusUi(isActive: Boolean) {
        if (binding.masterSwitch.isChecked != isActive) {
            isUpdatingSwitchProgrammatically = true
            binding.masterSwitch.isChecked = isActive
            isUpdatingSwitchProgrammatically = false
        }

        if (isActive) {
            binding.statusDot.setBackgroundResource(R.drawable.badge_active)
            binding.statusText.setText(R.string.status_monitoring_active)
            binding.masterSwitchDesc.setText(R.string.status_desc_active)
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.badge_inactive)
            binding.statusText.setText(R.string.status_monitoring_inactive)
            binding.masterSwitchDesc.setText(R.string.status_desc_inactive)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return fineLocation && notificationPermission
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updatePermissionBanner() {
        val hasPermissions = hasRequiredPermissions()
        binding.permissionBannerCard.visibility = if (hasPermissions) View.GONE else View.VISIBLE
    }

    override fun onSpeedStateUpdated(
        isRunning: Boolean,
        speedKmh: Float,
        volumePercent: Int,
        volumeLevel: Int,
        maxVolumeLevel: Int
    ) {
        updateStatusUi(isRunning)

        val displaySpeed = appSettings.kmhToDisplay(speedKmh)
        binding.liveSpeedValueText.text = String.format("%.1f", displaySpeed)
        binding.liveSpeedUnitText.text = appSettings.speedUnit.label

        binding.liveVolumePercentText.text = "$volumePercent%"
        binding.liveVolumeLevelText.text = "Stream: $volumeLevel / $maxVolumeLevel"
        binding.liveVolumeProgress.progress = volumePercent
    }
}