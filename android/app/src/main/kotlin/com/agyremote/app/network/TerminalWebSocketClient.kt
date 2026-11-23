package com.agyremote.app.network

import com.agyremote.app.model.AppJson
import com.agyremote.app.model.ConnectionState
import com.agyremote.app.model.PairedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface TerminalEvent {
    data class Output(val sessionId: String, val data: String, val seq: Long) : TerminalEvent
    data class Attached(val sessionId: String, val replay: String, val lastSeq: Long) : TerminalEvent
    data class SessionExit(val sessionId: String, val exitCode: Int?) : TerminalEvent
    data class ServerError(val code: String, val message: String) : TerminalEvent
}

/**
 * Persistent WebSocket client with automatic JWT refresh, exponential backoff reconnect,
 * and lossless delta scrollback replay (via sinceSeq).
 */
class TerminalWebSocketClient(
    private val apiClient: CompanionApiClient,
    private val onHostUpdated: (PairedHost) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconnectPolicy = ReconnectPolicy()
    private val sequenceTracker = SessionSequenceTracker()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()

    private var activeHost: PairedHost? = null
    private var activeSessionId: String? = null
    private var lastCols: Int = 80
    private var lastRows: Int = 24
    private var socket: WebSocket? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var manuallyClosed = false

    fun connect(host: PairedHost, sessionId: String, cols: Int = 80, rows: Int = 24) {
        manuallyClosed = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        val switchingSession = activeSessionId != sessionId || activeHost?.id != host.id
        if (switchingSession) {
            sequenceTracker.clear(sessionId)
        }
        activeHost = host
        activeSessionId = sessionId
        lastCols = cols
        lastRows = rows
        reconnectPolicy.reset()
        openSocket()
    }

    fun forceReconnect() {
        val host = activeHost ?: return
        val sessionId = activeSessionId ?: return
        manuallyClosed = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        val oldSocket = socket
        socket = null
        oldSocket?.cancel()
        reconnectPolicy.reset()
        activeHost = host
        activeSessionId = sessionId
        openSocket()
    }

    fun sendInput(data: String) {
        val sid = activeSessionId ?: return
        sendJson(
            buildJsonObject {
                put("type", "input")
                put("sessionId", sid)
                put("data", data)
            },
        )
    }

    fun sendResize(cols: Int, rows: Int) {
        lastCols = cols
        lastRows = rows
        val sid = activeSessionId ?: return
        sendJson(
            buildJsonObject {
                put("type", "resize")
                put("sessionId", sid)
                put("cols", cols)
                put("rows", rows)
            },
        )
    }

    fun disconnect() {
        manuallyClosed = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        val sid = activeSessionId
        if (sid != null) {
            sendJson(buildJsonObject {
                put("type", "detach")
                put("sessionId", sid)
            })
        }
        val oldSocket = socket
        socket = null
        oldSocket?.close(1000, "Client detached")
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun openSocket() {
        val host = activeHost ?: return
        val sessionId = activeSessionId ?: return

        connectJob?.cancel()
        connectJob = scope.launch {
            _connectionState.value = ConnectionState.Connecting
            val freshHost = try {
                apiClient.ensureFreshHost(host) { updated ->
                    activeHost = updated
                    onHostUpdated(updated)
                }
            } catch (err: Exception) {
                if (err is ApiException && err.statusCode == 401) {
                    _connectionState.value = ConnectionState.AuthError("Session revoked or expired. Please re-pair.")
                    return@launch
                }
                scheduleReconnect()
                return@launch
            }

            val req = Request.Builder()
                .url(freshHost.toWebSocketUrl())
                .header("Authorization", "Bearer ${freshHost.accessToken}")
                .build()

            val previousSocket = socket
            socket = null
            previousSocket?.cancel()
            socket = apiClient.okHttpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (webSocket !== socket) return
                        reconnectPolicy.reset()
                        _connectionState.value = ConnectionState.Connected(freshHost.name)

                        // Attach with sinceSeq so we only replay missed output on reconnect
                        val sinceSeq = sequenceTracker.lastSeq(sessionId)
                        sendJson(
                            buildJsonObject {
                                put("type", "attach")
                                put("sessionId", sessionId)
                                put("cols", lastCols)
                                put("rows", lastRows)
                                if (sinceSeq != null) put("sinceSeq", sinceSeq)
                            },
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (webSocket !== socket) return
                        handleIncoming(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (webSocket !== socket) return
                        webSocket.close(1000, null)
                        if (code == 4003) {
                            manuallyClosed = true
                            _connectionState.value = ConnectionState.AuthError("Device was revoked on PC.")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (webSocket !== socket) return
                        if (!manuallyClosed && code != 4003) {
                            scheduleReconnect()
                        } else if (code != 4003) {
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (webSocket !== socket) return
                        if (response?.code == 401) {
                            _connectionState.value = ConnectionState.AuthError("Authentication failed (401). Please re-pair.")
                            return
                        }
                        if (!manuallyClosed) {
                            scheduleReconnect()
                        }
                    }
                },
            )
        }
    }

    private fun handleIncoming(raw: String) {
        val obj = runCatching { AppJson.decodeFromString<JsonObject>(raw) }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        when (type) {
            "output" -> {
                val sid = obj["sessionId"]?.jsonPrimitive?.content ?: return
                val data = obj["data"]?.jsonPrimitive?.content ?: ""
                val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L
                sequenceTracker.record(sid, seq)
                _events.tryEmit(TerminalEvent.Output(sid, data, seq))
            }
            "attached" -> {
                val sid = activeSessionId ?: return
                val replay = obj["replay"]?.jsonPrimitive?.content ?: ""
                val lastSeq = obj["lastSeq"]?.jsonPrimitive?.longOrNull ?: 0L
                sequenceTracker.record(sid, lastSeq)
                _events.tryEmit(TerminalEvent.Attached(sid, replay, lastSeq))
            }
            "session_exit" -> {
                val sid = obj["sessionId"]?.jsonPrimitive?.content ?: return
                val exitCode = obj["exitCode"]?.jsonPrimitive?.intOrNull
                _events.tryEmit(TerminalEvent.SessionExit(sid, exitCode))
            }
            "error" -> {
                val code = obj["code"]?.jsonPrimitive?.content ?: "ERROR"
                val message = obj["message"]?.jsonPrimitive?.content ?: "Unknown error"
                _events.tryEmit(TerminalEvent.ServerError(code, message))
            }
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        reconnectJob?.cancel()
        val delayMs = reconnectPolicy.nextDelayMs()
        val attempt = reconnectPolicy.attempts
        _connectionState.value = ConnectionState.Reconnecting(attempt, delayMs)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manuallyClosed) {
                openSocket()
            }
        }
    }

    private fun sendJson(payload: JsonObject) {
        socket?.send(payload.toString())
    }
}
