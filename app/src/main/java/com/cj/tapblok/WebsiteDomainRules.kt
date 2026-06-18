package com.cj.tapblok

import app.olauncher.BuildConfig
import app.olauncher.R

import java.net.IDN
import java.util.Locale

object WebsiteDomainRules {
    fun normalizeDomain(input: String): String? {
        var candidate = input.trim().lowercase(Locale.US)
        if (candidate.isBlank()) return null

        candidate = candidate.substringAfter("://", candidate)
        candidate = candidate.substringBefore("/")
        candidate = candidate.substringBefore("?")
        candidate = candidate.substringBefore("#")
        candidate = candidate.substringBefore(":")
        candidate = candidate.trim().trim('.')
        candidate = candidate.removePrefix("*.").removePrefix("www.")

        if (candidate.isBlank()) return null

        val ascii = runCatching {
            IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
        }.getOrNull() ?: return null

        val labels = ascii.split(".")
        if (labels.size < 2) return null
        if (labels.any { label ->
                label.isBlank() ||
                    label.length > 63 ||
                    label.startsWith("-") ||
                    label.endsWith("-") ||
                    label.any { char -> !(char.isLetterOrDigit() || char == '-') }
            }
        ) {
            return null
        }
        if (ascii.length > 253) return null

        return ascii
    }

    fun isBlocked(host: String, blockedDomains: Set<String>): Boolean {
        val normalizedHost = normalizeHost(host) ?: return false
        return blockedDomains.any { blockedDomain ->
            normalizedHost == blockedDomain || normalizedHost.endsWith(".$blockedDomain")
        }
    }

    private fun normalizeHost(input: String): String? {
        var candidate = input.trim().lowercase(Locale.US)
        candidate = candidate.substringBefore(":").trim().trim('.')
        if (candidate.isBlank()) return null
        return runCatching {
            IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.US)
        }.getOrNull()
    }
}
