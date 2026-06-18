package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

object HardModeManager {
    private const val TAG = "HardModeManager"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, TapBlokDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun setSelfUninstallBlocked(context: Context, blocked: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false

        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setUninstallBlocked(adminComponent(context), context.packageName, blocked)
            true
        }.onFailure {
            Log.w(TAG, "Unable to update self uninstall block", it)
        }.getOrDefault(false)
    }

    fun setBlockedPackagesSuspended(
        context: Context,
        packageNames: Set<String>,
        suspended: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isDeviceOwner(context)) return false

        val safePackages = packageNames
            .filterNot { ProtectedPackages.isProtected(it, context.packageName) }
            .distinct()
            .toTypedArray()

        if (safePackages.isEmpty()) return true

        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val failedPackages = dpm.setPackagesSuspended(
                adminComponent(context),
                safePackages,
                suspended
            )
            if (failedPackages.isNotEmpty()) {
                Log.w(TAG, "Could not ${if (suspended) "suspend" else "unsuspend"}: ${failedPackages.joinToString()}")
            }
            failedPackages.isEmpty()
        }.onFailure {
            Log.w(TAG, "Unable to update package suspension", it)
        }.getOrDefault(false)
    }
}
