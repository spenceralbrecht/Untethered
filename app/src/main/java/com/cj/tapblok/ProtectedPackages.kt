package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

object ProtectedPackages {
    private val EXACT_PACKAGES = setOf(
        "com.android.dialer",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.android.providers.downloads",
        "com.android.settings",
        "com.android.systemui",
        "com.android.vending",
        "com.google.android.apps.messaging",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.dialer",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.setupwizard",
        "com.google.android.webview",
        "com.oneplus.dialer",
        "com.oneplus.launcher",
        "com.oneplus.mms",
        "com.oneplus.packageinstaller",
        "com.oneplus.security",
        "com.oplus.dialer",
        "com.oplus.launcher",
        "com.oplus.mms",
        "com.oplus.packageinstaller",
        "com.oplus.securitycenter",
        "com.samsung.android.dialer",
        "com.samsung.android.messaging",
        "com.sec.android.app.launcher",
        "com.sonyericsson.android.socialphonebook"
    )

    private val PACKAGE_PREFIXES = listOf(
        "com.android.emergency",
        "com.android.phone",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.oneplus.settings",
        "com.oplus.settings"
    )

    fun isProtected(packageName: String, selfPackageName: String): Boolean =
        packageName == selfPackageName ||
            packageName in EXACT_PACKAGES ||
            PACKAGE_PREFIXES.any { packageName.startsWith(it) }
}
