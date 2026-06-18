package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

object UnlockTokenManager {
    private const val PREFS = "app_prefs"
    private const val KEY_UNLOCK_TOKEN = "unlock_token"
    private const val TOKEN_BYTES = 32

    fun getOrCreateUnlockToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_UNLOCK_TOKEN, null)?.let { return it }

        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit { putString(KEY_UNLOCK_TOKEN, token) }
        return token
    }

    fun isValidUnlockToken(context: Context, candidate: String): Boolean {
        val expected = getOrCreateUnlockToken(context).toByteArray(Charsets.UTF_8)
        val actual = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, actual)
    }
}
