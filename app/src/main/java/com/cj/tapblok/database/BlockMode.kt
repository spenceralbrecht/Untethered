package com.cj.tapblok.database

import app.untethered.BuildConfig
import app.untethered.R

object BlockMode {
    const val HOME_AWARE = "HOME_AWARE"
    const val ALWAYS = "ALWAYS"

    fun label(mode: String): String =
        if (mode == ALWAYS) "Always blocked" else "Allowed at home"

    fun shortLabel(mode: String): String =
        if (mode == ALWAYS) "Always" else "Home-aware"

    fun isAlways(mode: String): Boolean = mode == ALWAYS
}
