package com.cj.tapblok.database

import app.untethered.BuildConfig
import app.untethered.R

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_websites")
data class BlockedWebsite(
    @PrimaryKey
    val domain: String,
    val blockMode: String = BlockMode.HOME_AWARE
)
