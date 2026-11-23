package com.agyremote.app.network

import com.agyremote.app.model.AppJson
import com.agyremote.app.model.AuthResponse
import com.agyremote.app.model.CreateSessionResponse
import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.SessionInfo
import com.agyremote.app.model.SessionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(val statusCode: Int, message: String) : IOException(message)

class CompanionApiClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val refreshMutex = Mutex()
    private val refreshedHosts = mutableMapOf<String, PairedHost>()

    suspend fun login(
        baseUrl: String,
        email: String,
        password: String,
        clientDeviceName: String = "Android Phone",
    ): PairedHost = withContext(Dispatchers.IO) {
        val url = normalizeBaseUrl(baseUrl)
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            put("deviceName", clientDeviceName)
        }
        val auth = postJson<AuthResponse>("$url/api/auth/login", body)
        PairedHost(
            id = UUID.randomUUID().toString(),
            name = auth.hostDeviceName ?: url.removePrefix("https://").removePrefix("http://"),
            baseUrl = url,
            deviceId = auth.deviceId,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            tokenExpiresAtMs = System.currentTimeMillis() + auth.expiresInSec * 1000L,
            platform = auth.platform ?: "win32",
        )
    }

    suspend fun pair(
        baseUrl: String,
        pairToken: String,
        fallbackHostName: String = "My PC",
        clientDeviceName: String = "Android Phone",
    ): PairedHost = withContext(Dispatchers.IO) {
        val url = normalizeBaseUrl(baseUrl)
        val body = buildJsonObject {
            put("pairToken", pairToken.trim())
            put("deviceName", clientDeviceName)
        }
        val auth = postJson<AuthResponse>("$url/api/auth/pair", body)
        PairedHost(
            id = UUID.randomUUID().toString(),
            name = auth.hostDeviceName ?: fallbackHostName,
            baseUrl = url,
            deviceId = auth.deviceId,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            tokenExpiresAtMs = System.currentTimeMillis() + auth.expiresInSec * 1000L,
            platform = auth.platform ?: "win32",
        )
    }

    suspend fun refreshHost(host: PairedHost): PairedHost = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("deviceId", host.deviceId)
            put("refreshToken", host.refreshToken)
        }
        val auth = postJson<AuthResponse>("${host.baseUrl.trimEnd('/')}/api/auth/refresh", body)
        host.copy(
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken,
            tokenExpiresAtMs = System.currentTimeMillis() + auth.expiresInSec * 1000L,
        )
    }

    suspend fun ensureFreshHost(
        host: PairedHost,
        onHostUpdated: (PairedHost) -> Unit,
    ): PairedHost {
        if (!host.isAccessTokenExpired()) return host
        return refreshMutex.withLock {
            val cached = refreshedHosts[host.id]
            if (cached != null && !cached.isAccessTokenExpired()) {
                onHostUpdated(cached)
                return@withLock cached
            }
            val refreshed = refreshHost(host)
            refreshedHosts[host.id] = refreshed
            onHostUpdated(refreshed)
            refreshed
        }
    }

    suspend fun listSessions(
        host: PairedHost,
        onHostUpdated: (PairedHost) -> Unit,
    ): List<SessionInfo> = withContext(Dispatchers.IO) {
        val active = ensureFreshHost(host, onHostUpdated)
        val req = Request.Builder()
            .url("${active.baseUrl.trimEnd('/')}/api/sessions")
            .header("Authorization", "Bearer ${active.accessToken}")
            .get()
            .build()
        executeJson<SessionsResponse>(req).sessions
    }

    suspend fun createSession(
        host: PairedHost,
        name: String?,
        cwd: String?,
        command: String?,
        onHostUpdated: (PairedHost) -> Unit,
    ): SessionInfo = withContext(Dispatchers.IO) {
        val active = ensureFreshHost(host, onHostUpdated)
        val body = buildJsonObject {
            if (!name.isNullOrBlank()) put("name", name.trim())
            if (!cwd.isNullOrBlank()) put("cwd", cwd.trim())
            if (!command.isNullOrBlank()) put("command", command.trim())
            put("cols", 100)
            put("rows", 30)
        }
        postJson<CreateSessionResponse>(
            "${active.baseUrl.trimEnd('/')}/api/sessions",
            body,
            bearerToken = active.accessToken,
        ).session
    }

    suspend fun killSession(
        host: PairedHost,
        sessionId: String,
        onHostUpdated: (PairedHost) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val active = ensureFreshHost(host, onHostUpdated)
        val req = Request.Builder()
            .url("${active.baseUrl.trimEnd('/')}/api/sessions/$sessionId")
            .header("Authorization", "Bearer ${active.accessToken}")
            .delete()
            .build()
        okHttpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful && res.code != 404) {
                throw ApiException(res.code, "Failed to close session (${res.code})")
            }
        }
    }

    suspend fun revokeDevice(
        host: PairedHost,
        onHostUpdated: (PairedHost) -> Unit,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val active = ensureFreshHost(host, onHostUpdated)
            val req = Request.Builder()
                .url("${active.baseUrl.trimEnd('/')}/api/devices/${active.deviceId}")
                .header("Authorization", "Bearer ${active.accessToken}")
                .delete()
                .build()
            okHttpClient.newCall(req).execute().close()
        }
    }

    private inline fun <reified T> postJson(
        url: String,
        payload: JsonObject,
        bearerToken: String? = null,
    ): T {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
        if (!bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $bearerToken")
        }
        return executeJson(reqBuilder.build())
    }

    private inline fun <reified T> executeJson(request: Request): T {
        okHttpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching {
                    val obj = AppJson.decodeFromString<JsonObject>(raw)
                    obj["error"]?.toString()?.trim('"')
                }.getOrNull() ?: "HTTP ${response.code}"
                throw ApiException(response.code, msg)
            }
            return AppJson.decodeFromString<T>(raw)
        }
    }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return when {
                trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
        }
    }
}
