package com.agyremote.app.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agyremote.app.model.ConnectionState
import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.QrPairingPayload
import com.agyremote.app.model.QuickCommand
import com.agyremote.app.model.SessionInfo
import com.agyremote.app.network.CompanionApiClient
import com.agyremote.app.network.TerminalEvent
import com.agyremote.app.network.TerminalWebSocketClient
import com.agyremote.app.security.SecureStorage
import com.agyremote.app.service.TerminalConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AppScreen {
    data object LoginOrPair : AppScreen
    data object DevicesAndSessions : AppScreen
    data class Terminal(val host: PairedHost, val session: SessionInfo) : AppScreen
}

data class AppUiState(
    val screen: AppScreen = AppScreen.LoginOrPair,
    val hosts: List<PairedHost> = emptyList(),
    val selectedHostId: String? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val quickCommands: List<QuickCommand> = QuickCommand.defaults(),
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedHost: PairedHost?
        get() = hosts.firstOrNull { it.id == selectedHostId } ?: hosts.firstOrNull()
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = SecureStorage(application)
    private val apiClient = CompanionApiClient()

    private val wsClient = TerminalWebSocketClient(apiClient) { updatedHost ->
        val updated = storage.upsertHost(updatedHost)
        _uiState.update { it.copy(hosts = updated) }
    }

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState
    val terminalEvents: SharedFlow<TerminalEvent> = wsClient.events

    init {
        val hosts = storage.loadHosts()
        val quickCommands = storage.loadQuickCommands()
        val biometric = storage.isBiometricEnabled()
        val initialScreen = if (hosts.isEmpty()) AppScreen.LoginOrPair else AppScreen.DevicesAndSessions

        _uiState.value = AppUiState(
            screen = initialScreen,
            hosts = hosts,
            selectedHostId = hosts.firstOrNull()?.id,
            quickCommands = quickCommands,
            biometricEnabled = biometric,
        )

        if (hosts.isNotEmpty()) {
            refreshSessions()
        }

        // Mirror connection state into Foreground Service notification when in terminal
        viewModelScope.launch {
            connectionState.collect { conn ->
                val currentScreen = _uiState.value.screen
                if (currentScreen is AppScreen.Terminal) {
                    val statusText = when (conn) {
                        is ConnectionState.Connected -> "Connected"
                        is ConnectionState.Connecting -> "Connecting..."
                        is ConnectionState.Reconnecting -> "Reconnecting (#${conn.attempt})..."
                        is ConnectionState.AuthError -> "Auth error"
                        ConnectionState.Disconnected -> "Disconnected"
                    }
                    TerminalConnectionService.start(
                        getApplication(),
                        currentScreen.host.name,
                        currentScreen.session.name,
                        statusText,
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun openAddHost() {
        _uiState.update { it.copy(screen = AppScreen.LoginOrPair, errorMessage = null) }
    }

    fun backToDevices() {
        val prev = _uiState.value.screen
        if (prev is AppScreen.Terminal) {
            wsClient.disconnect()
            TerminalConnectionService.stop(getApplication())
        }
        _uiState.update {
            it.copy(
                screen = if (it.hosts.isEmpty()) AppScreen.LoginOrPair else AppScreen.DevicesAndSessions,
                errorMessage = null,
            )
        }
        if (_uiState.value.hosts.isNotEmpty()) {
            refreshSessions()
        }
    }

    fun selectHost(hostId: String) {
        _uiState.update { it.copy(selectedHostId = hostId, sessions = emptyList(), errorMessage = null) }
        refreshSessions()
    }

    fun pairFromQr(rawQr: String) {
        val parsed = QrPairingPayload.parse(rawQr)
        if (parsed.isFailure) {
            _uiState.update { it.copy(errorMessage = parsed.exceptionOrNull()?.message ?: "Invalid QR code") }
            return
        }
        val payload = parsed.getOrThrow()
        pairManual(payload.url, payload.pairToken, payload.deviceName)
    }

    fun pairManual(baseUrl: String, pairToken: String, fallbackName: String = "My PC") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                apiClient.pair(
                    baseUrl = baseUrl,
                    pairToken = pairToken,
                    fallbackHostName = fallbackName,
                    clientDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                )
            }.onSuccess { host ->
                val hosts = storage.upsertHost(host)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hosts = hosts,
                        selectedHostId = host.id,
                        screen = AppScreen.DevicesAndSessions,
                    )
                }
                refreshSessions()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Pairing failed") }
            }
        }
    }

    fun loginWithPassword(baseUrl: String, email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                apiClient.login(
                    baseUrl = baseUrl,
                    email = email,
                    password = password,
                    clientDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                )
            }.onSuccess { host ->
                val hosts = storage.upsertHost(host)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hosts = hosts,
                        selectedHostId = host.id,
                        screen = AppScreen.DevicesAndSessions,
                    )
                }
                refreshSessions()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Login failed") }
            }
        }
    }

    fun removeAndRevokeHost(host: PairedHost) {
        viewModelScope.launch {
            apiClient.revokeDevice(host) { updated -> storage.upsertHost(updated) }
            val remaining = storage.removeHost(host.id)
            _uiState.update {
                it.copy(
                    hosts = remaining,
                    selectedHostId = remaining.firstOrNull()?.id,
                    sessions = emptyList(),
                    screen = if (remaining.isEmpty()) AppScreen.LoginOrPair else AppScreen.DevicesAndSessions,
                )
            }
            if (remaining.isNotEmpty()) refreshSessions()
        }
    }

    fun refreshSessions() {
        val host = _uiState.value.selectedHost ?: return
        val requestedHostId = host.id
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                apiClient.listSessions(host) { updated ->
                    val hosts = storage.upsertHost(updated)
                    _uiState.update { it.copy(hosts = hosts) }
                }
            }.onSuccess { sessions ->
                _uiState.update {
                    if (it.selectedHost?.id == requestedHostId) {
                        it.copy(isLoading = false, sessions = sessions)
                    } else {
                        it.copy(isLoading = false)
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    if (it.selectedHost?.id == requestedHostId) {
                        it.copy(isLoading = false, errorMessage = err.message ?: "Failed to reach PC")
                    } else {
                        it.copy(isLoading = false)
                    }
                }
            }
        }
    }

    fun createAndOpenSession(name: String?, cwd: String?, command: String?) {
        val host = _uiState.value.selectedHost ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                apiClient.createSession(host, name, cwd, command) { updated ->
                    val hosts = storage.upsertHost(updated)
                    _uiState.update { it.copy(hosts = hosts) }
                }
            }.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sessions = listOf(session) + it.sessions,
                    )
                }
                openTerminal(session)
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Could not create session") }
            }
        }
    }

    fun killSession(sessionId: String) {
        val host = _uiState.value.selectedHost ?: return
        viewModelScope.launch {
            runCatching {
                apiClient.killSession(host, sessionId) { updated ->
                    val hosts = storage.upsertHost(updated)
                    _uiState.update { it.copy(hosts = hosts) }
                }
            }.onSuccess {
                val current = _uiState.value.screen
                if (current is AppScreen.Terminal && current.session.id == sessionId) {
                    backToDevices()
                } else {
                    _uiState.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == sessionId }) }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to close session") }
            }
        }
    }

    fun openTerminal(session: SessionInfo) {
        val host = _uiState.value.selectedHost ?: return
        _uiState.update { it.copy(screen = AppScreen.Terminal(host, session), errorMessage = null) }
        TerminalConnectionService.start(getApplication(), host.name, session.name, "Connecting...")
        wsClient.connect(host, session.id)
    }

    fun sendTerminalInput(data: String) = wsClient.sendInput(data)

    fun sendTerminalResize(cols: Int, rows: Int) = wsClient.sendResize(cols, rows)

    fun reconnectTerminal() = wsClient.forceReconnect()

    fun setBiometricEnabled(enabled: Boolean) {
        storage.setBiometricEnabled(enabled)
        _uiState.update { it.copy(biometricEnabled = enabled) }
    }

    fun addQuickCommand(label: String, command: String) {
        val next = _uiState.value.quickCommands + QuickCommand(
            id = UUID.randomUUID().toString(),
            label = label.trim(),
            command = command,
            appendNewline = !command.endsWith(" "),
        )
        storage.saveQuickCommands(next)
        _uiState.update { it.copy(quickCommands = next) }
    }

    fun removeQuickCommand(id: String) {
        val next = _uiState.value.quickCommands.filterNot { it.id == id }
        storage.saveQuickCommands(next)
        _uiState.update { it.copy(quickCommands = next) }
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
        TerminalConnectionService.stop(getApplication())
    }
}
