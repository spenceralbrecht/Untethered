package com.cj.tapblok

import app.olauncher.BuildConfig
import app.olauncher.R

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_USER_PRESENT ||
            intent.action == Intent.ACTION_POWER_CONNECTED
        ) {
            Log.d("BootCompletedReceiver", "System event ${intent.action}; enforcing default monitoring.")
            ensureMonitoringDefaultEnabled(context)
        }
    }
}
