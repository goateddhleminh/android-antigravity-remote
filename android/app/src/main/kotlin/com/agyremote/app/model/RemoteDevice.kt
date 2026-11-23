package com.agyremote.app.model

import kotlinx.serialization.Serializable
import java.net.URI

const val ANTIGRAVITY_REMOTE_URL = "https://antigravity.google.com"

@Serializable
data class RemoteDevice(
    val id: String,
    val name: String,
    val url: String,
    val addedAtMs: Long = System.currentTimeMillis(),
)

object RemoteLinkParser {
    /**
     * Parses a QR code or "copy link" URL from Google Antigravity Settings -> Remote Control.
     * Only accepts safe https:// URLs.
     */
    fun parse(raw: String, customName: String? = null): Result<Pair<String, String>> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Link or QR code is empty" }

        val normalized = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("antigravity.google", ignoreCase = true) -> "https://$trimmed"
            else -> throw IllegalArgumentException("Expected an https:// Antigravity Remote Control link")
        }

        val uri = URI(normalized)
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Invalid URL host")
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS links are allowed" }

        val inferredName = customName?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractDeviceNameFromUri(uri)
            ?: if (host.contains("antigravity.google")) "My Antigravity PC" else host

        inferredName to normalized
    }

    private fun extractDeviceNameFromUri(uri: URI): String? {
        val query = uri.rawQuery.orEmpty()
        if (query.isNotBlank()) {
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].lowercase()
                    val value = kv[1].trim()
                    if (key in listOf("device", "devicename", "host", "hostname", "instance", "name") && value.isNotEmpty()) {
                        return java.net.URLDecoder.decode(value, "UTF-8")
                    }
                }
            }
        }
        val segments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }
        val last = segments.lastOrNull()
        if (last != null && last.contains("-") && last.length in 5..64) {
            return last
        }
        return null
    }
}
