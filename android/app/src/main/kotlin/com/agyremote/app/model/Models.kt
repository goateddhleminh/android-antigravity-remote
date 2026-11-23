package com.agyremote.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

@Serializable
data class PairedHost(
    val id: String,
    val name: String,
    val baseUrl: String,
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenExpiresAtMs: Long,
    val platform: String = "win32",
) {
    fun isAccessTokenExpired(nowMs: Long = System.currentTimeMillis(), skewMs: Long = 30_000L): Boolean {
        return nowMs + skewMs >= tokenExpiresAtMs
    }

    fun toWebSocketUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "wss://$trimmed"
        }
        return "$wsBase/ws"
    }
}

@Serializable
data class QrPairingPayload(
    val url: String,
    val deviceName: String = "My PC",
    val pairToken: String,
) {
    companion object {
        fun parse(raw: String): Result<QrPairingPayload> = runCatching {
            val payload = AppJson.decodeFromString<QrPairingPayload>(raw.trim())
            require(payload.url.isNotBlank()) { "QR payload missing server url" }
            require(payload.pairToken.isNotBlank()) { "QR payload missing pairToken" }
            payload
        }
    }
}

@Serializable
data class AuthResponse(
    val deviceId: String,
    val deviceName: String = "Android",
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long = 900,
    val hostDeviceName: String? = null,
    val platform: String? = null,
)

@Serializable
data class SessionInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val shell: String,
    val cols: Int,
    val rows: Int,
    val createdAt: String,
    val lastActivityAt: String,
    val alive: Boolean,
    val exitCode: Int? = null,
    val attachedClients: Int = 0,
)

@Serializable
data class SessionsResponse(
    val sessions: List<SessionInfo> = emptyList(),
)

@Serializable
data class CreateSessionResponse(
    val session: SessionInfo,
)

@Serializable
data class QuickCommand(
    val id: String,
    val label: String,
    val command: String,
    val appendNewline: Boolean = true,
) {
    companion object {
        fun defaults(): List<QuickCommand> = listOf(
            QuickCommand("qc-agy", "agy", "agy"),
            QuickCommand("qc-plan", "agy plan", "agy --mode=plan"),
            QuickCommand("qc-auto", "agy edits", "agy --mode=accept-edits"),
            QuickCommand("qc-goal", "/goal", "/goal "),
            QuickCommand("qc-git", "git status", "git status"),
            QuickCommand("qc-clear", "clear", "clear"),
        )
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hostName: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : ConnectionState
    data class AuthError(val message: String) : ConnectionState
}
