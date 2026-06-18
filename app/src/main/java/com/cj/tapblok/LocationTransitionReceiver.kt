package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class LocationTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            Log.w("LocationTransition", "Geofence event error: ${event?.errorCode}")
            return
        }

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> LocationRuleManager.applyHomePolicy(context, insideHome = true)
            Geofence.GEOFENCE_TRANSITION_EXIT -> LocationRuleManager.applyHomePolicy(context, insideHome = false)
            else -> Log.d("LocationTransition", "Ignoring transition: ${event.geofenceTransition}")
        }
    }
}
