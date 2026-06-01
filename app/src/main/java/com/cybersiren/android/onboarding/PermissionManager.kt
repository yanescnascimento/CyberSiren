package com.cybersiren.android.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cybersiren.android.R

class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
        private const val PREFS_NAME = "bitchat_permissions"
        private const val KEY_FIRST_TIME_COMPLETE = "first_time_onboarding_complete"
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFirstTimeLaunch(): Boolean {
        return !sharedPrefs.getBoolean(KEY_FIRST_TIME_COMPLETE, false)
    }

    fun markOnboardingComplete() {
        sharedPrefs.edit()
            .putBoolean(KEY_FIRST_TIME_COMPLETE, true)
            .apply()
        Log.d(TAG, "First-time onboarding marked as complete")
    }

    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        return permissions
    }

    fun needsBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun getBackgroundLocationPermission(): String? {
        return if (needsBackgroundLocationPermission()) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }
    }

    fun isBackgroundLocationGranted(): Boolean {
        val permission = getBackgroundLocationPermission() ?: return true
        return isPermissionGranted(permission)
    }

    fun getOptionalPermissions(): List<String> {
        val optional = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optional.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return optional
    }

    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun areAllPermissionsGranted(): Boolean {
        return areRequiredPermissionsGranted()
    }

    fun areRequiredPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(it) }
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                false
            }
        } else {

            true
        }
    }

    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { !isPermissionGranted(it) }
    }

    fun getMissingBackgroundLocationPermission(): List<String> {
        val permission = getBackgroundLocationPermission() ?: return emptyList()
        return if (isPermissionGranted(permission)) emptyList() else listOf(permission)
    }

    fun getCategorizedPermissions(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()

        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        categories.add(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                description = context.getString(R.string.perm_nearby_devices_desc),
                permissions = bluetoothPermissions,
                isGranted = bluetoothPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_nearby_devices_system)
            )
        )

        val locationPermissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        categories.add(
            PermissionCategory(
                type = PermissionType.PRECISE_LOCATION,
                description = context.getString(R.string.perm_location_desc),
                permissions = locationPermissions,
                isGranted = locationPermissions.all { isPermissionGranted(it) },
                systemDescription = context.getString(R.string.perm_location_system)
            )
        )

        if (needsBackgroundLocationPermission()) {
            val backgroundPermission = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            categories.add(
                PermissionCategory(
                    type = PermissionType.BACKGROUND_LOCATION,
                    description = context.getString(R.string.perm_background_location_desc),
                    permissions = backgroundPermission,
                    isGranted = backgroundPermission.all { isPermissionGranted(it) },
                    systemDescription = context.getString(R.string.perm_background_location_system)
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.NOTIFICATIONS,
                    description = context.getString(R.string.perm_notifications_desc),
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS),
                    systemDescription = context.getString(R.string.perm_notifications_system)
                )
            )
        }

        if (isBatteryOptimizationSupported()) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.BATTERY_OPTIMIZATION,
                    description = context.getString(R.string.perm_battery_desc),
                    permissions = listOf("BATTERY_OPTIMIZATION"),
                    isGranted = isBatteryOptimizationDisabled(),
                    systemDescription = context.getString(R.string.perm_battery_system)
                )
            )
        }

        return categories
    }

    fun getPermissionDiagnostics(): String {
        return buildString {
            appendLine("Permission Diagnostics:")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("First time launch: ${isFirstTimeLaunch()}")
            appendLine("Required permissions granted: ${areAllPermissionsGranted()}")
            appendLine()

            getCategorizedPermissions().forEach { category ->
                appendLine("${category.type.nameValue}: ${if (category.isGranted) "GRANTED" else "MISSING"}")
                category.permissions.forEach { permission ->
                    val granted = isPermissionGranted(permission)
                    appendLine("  - ${permission.substringAfterLast(".")}: ${if (granted) "" else ""}")
                }
                appendLine()
            }

            val missing = getMissingPermissions() + getMissingBackgroundLocationPermission()
            if (missing.isNotEmpty()) {
                appendLine("Missing permissions:")
                missing.forEach { permission ->
                    appendLine("- $permission")
                }
            }
        }
    }

    fun logPermissionStatus() {
        Log.d(TAG, getPermissionDiagnostics())
    }
}

data class PermissionCategory(
    val type: PermissionType,
    val description: String,
    val permissions: List<String>,
    val isGranted: Boolean,
    val systemDescription: String
)

enum class PermissionType(val nameValue: String) {
    NEARBY_DEVICES("Nearby Devices"),
    PRECISE_LOCATION("Precise Location"),
    BACKGROUND_LOCATION("Background Location"),
    MICROPHONE("Microphone"),
    NOTIFICATIONS("Notifications"),
    BATTERY_OPTIMIZATION("Battery Optimization"),
    OTHER("Other")
}
