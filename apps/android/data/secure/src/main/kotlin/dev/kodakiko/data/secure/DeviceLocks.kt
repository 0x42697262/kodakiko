package dev.kodakiko.data.secure

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build

object DeviceLocks {
    fun current(context: Context): DeviceLock {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return DeviceLock.NONE
        return if (strongBiometricEnrolled(context)) DeviceLock.BIOMETRIC else DeviceLock.SECURE
    }

    private fun strongBiometricEnrolled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
