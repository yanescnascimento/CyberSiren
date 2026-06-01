package com.cybersiren.android.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class OnboardingCoordinator(
    private val activity: ComponentActivity,
    private val permissionManager: PermissionManager,
    private val onOnboardingComplete: () -> Unit,
    private val onBackgroundLocationRequired: () -> Unit,
    private val onOnboardingFailed: (String) -> Unit
) {

    companion object {
        private const val TAG = "OnboardingCoordinator"
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var backgroundLocationLauncher: ActivityResultLauncher<String>? = null

    init {
        setupPermissionLauncher()
        setupBackgroundLocationLauncher()
    }

    private fun setupPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResults(permissions)
        }
    }

    private fun setupBackgroundLocationLauncher() {
        backgroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            handleBackgroundLocationResult(granted)
        }
    }

    fun startOnboarding() {
        Log.d(TAG, "Starting onboarding process")
        permissionManager.logPermissionStatus()

        if (permissionManager.areRequiredPermissionsGranted()) {
            if (shouldRequestBackgroundLocation()) {
                Log.d(TAG, "Foreground permissions granted; background location recommended")
                onBackgroundLocationRequired()
            } else {
                Log.d(TAG, "Required permissions already granted, completing onboarding")
                completeOnboarding()
            }
        } else {
            Log.d(TAG, "Missing permissions, need to start explanation flow")

        }
    }

    fun requestPermissions() {
        Log.d(TAG, "User accepted permission explanation, requesting permissions")

        val missingRequired = permissionManager.getMissingPermissions()

        val optionalToRequest = permissionManager
            .getOptionalPermissions()
            .filter { !permissionManager.isPermissionGranted(it) }

        val missingPermissions = (missingRequired + optionalToRequest).distinct()

        if (missingPermissions.isEmpty()) {
            if (shouldRequestBackgroundLocation()) {
                onBackgroundLocationRequired()
            } else {
                completeOnboarding()
            }
            return
        }

        Log.d(TAG, "Requesting ${missingPermissions.size} permissions")
        permissionLauncher?.launch(missingPermissions.toTypedArray())
    }

    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        Log.d(TAG, "Received permission results:")
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }

        val allGranted = permissions.values.all { it }
        val criticalPermissions = getCriticalPermissions()
        val criticalGranted = criticalPermissions.all { permissions[it] == true }

        when {
            criticalGranted -> {
                if (shouldRequestBackgroundLocation()) {
                    Log.d(TAG, "Foreground permissions granted; requesting background location next")
                    onBackgroundLocationRequired()
                    return
                }
                if (allGranted) {
                    Log.d(TAG, "All permissions granted successfully")
                    completeOnboarding()
                } else {
                    Log.d(TAG, "Critical permissions granted, can proceed with limited functionality")
                    showPartialPermissionWarning(permissions)
                }
            }
            else -> {
                Log.d(TAG, "Critical permissions denied")
                handlePermissionDenial(permissions)
            }
        }
    }

    fun requestBackgroundLocation() {
        val permission = permissionManager.getBackgroundLocationPermission()
        if (permission == null) {
            completeOnboarding()
            return
        }
        Log.d(TAG, "Requesting background location permission")
        backgroundLocationLauncher?.launch(permission)
    }

    private fun handleBackgroundLocationResult(granted: Boolean) {
        if (granted) {
            Log.d(TAG, "Background location permission granted")
        } else {
            Log.w(TAG, "Background location permission denied; continuing without it")
        }
        completeOnboarding()
    }

    fun skipBackgroundLocation() {
        Log.d(TAG, "User skipped background location permission")
        BackgroundLocationPreferenceManager.setSkipped(activity, true)
        completeOnboarding()
    }

    fun checkBackgroundLocationAndProceed() {
        if (!shouldRequestBackgroundLocation()) {
            completeOnboarding()
        }
    }

    private fun shouldRequestBackgroundLocation(): Boolean {
        return permissionManager.needsBackgroundLocationPermission() &&
            !permissionManager.isBackgroundLocationGranted() &&
            !BackgroundLocationPreferenceManager.isSkipped(activity)
    }

    private fun getCriticalPermissions(): List<String> {

        return permissionManager.getRequiredPermissions()
    }

    private fun showPartialPermissionWarning(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys
        val message = buildString {
            append("Some permissions were denied:\n")
            deniedPermissions.forEach { permission ->
                append("- ${getPermissionDisplayName(permission)}\n")
            }
            append("\nbitchat may not work properly without all permissions.")
        }

        Log.w(TAG, "Partial permissions granted: $message")

        completeOnboarding()
    }

    private fun handlePermissionDenial(permissions: Map<String, Boolean>) {
        val deniedCritical = permissions.filter { !it.value && getCriticalPermissions().contains(it.key) }

        if (deniedCritical.isNotEmpty()) {
            val message = buildString {
                append("Critical permissions were denied. bitchat requires these permissions to function:\n")
                deniedCritical.keys.forEach { permission ->
                    append("- ${getPermissionDisplayName(permission)}\n")
                }
                append("\nPlease grant these permissions in Settings to use bitchat.")
            }

            Log.e(TAG, "Critical permissions denied: $deniedCritical")
            onOnboardingFailed(message)
        } else {

            completeOnboarding()
        }
    }

    private fun completeOnboarding() {
        Log.d(TAG, "Completing onboarding process")

        permissionManager.markOnboardingComplete()

        permissionManager.logPermissionStatus()

        activity.lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            onOnboardingComplete()
        }
    }

    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Log.d(TAG, "Opened app settings for manual permission management")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    private fun getPermissionDisplayName(permission: String): String {
        return when {
            permission.contains("BLUETOOTH") -> "Bluetooth/Nearby Devices"
            permission.contains("BACKGROUND") -> "Background Location"
            permission.contains("LOCATION") -> "Location (for Bluetooth scanning)"
            permission.contains("NOTIFICATION") -> "Notifications"
            else -> permission.substringAfterLast(".")
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Onboarding Coordinator Diagnostics:")
            appendLine("Activity: ${activity::class.simpleName}")
            appendLine("Permission launcher: ${permissionLauncher != null}")
            appendLine()
            append(permissionManager.getPermissionDiagnostics())
        }
    }
}
