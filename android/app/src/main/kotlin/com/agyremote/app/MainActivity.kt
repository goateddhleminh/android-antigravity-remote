package com.agyremote.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agyremote.app.model.RemoteDevice
import com.agyremote.app.model.RemoteLinkParser
import com.agyremote.app.security.BiometricHelper
import com.agyremote.app.security.SecureStorage
import com.agyremote.app.ui.AppScreen
import com.agyremote.app.ui.AppViewModel
import com.agyremote.app.ui.screens.DevicesAndSessionsScreen
import com.agyremote.app.ui.screens.LoginPairScreen
import com.agyremote.app.ui.screens.RemoteControlHomeScreen
import com.agyremote.app.ui.screens.RemoteWebViewScreen
import com.agyremote.app.ui.screens.TerminalScreen
import com.agyremote.app.ui.theme.AntigravityRemoteTheme
import java.util.UUID

class MainActivity : FragmentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val storage = SecureStorage(this)
        val biometricAvailable = BiometricHelper.canAuthenticate(this)

        setContent {
            AntigravityRemoteTheme {
                var devices by remember { mutableStateOf(storage.loadRemoteDevices()) }
                var useCustomTabs by rememberSaveable { mutableStateOf(storage.useCustomTabs()) }
                var biometricEnabled by rememberSaveable { mutableStateOf(storage.isBiometricEnabled()) }
                var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var activeWebTitle by rememberSaveable { mutableStateOf<String?>(null) }
                var activeWebUrl by rememberSaveable { mutableStateOf<String?>(null) }
                var companionMode by rememberSaveable { mutableStateOf(false) }

                var unlocked by rememberSaveable { mutableStateOf(!biometricEnabled || !biometricAvailable) }
                var authErr by rememberSaveable { mutableStateOf<String?>(null) }

                fun openRemote(title: String, url: String) {
                    errorMessage = null
                    if (useCustomTabs) {
                        launchDarkCustomTab(url)?.let { err -> errorMessage = err }
                    } else {
                        activeWebTitle = title
                        activeWebUrl = url
                    }
                }

                LaunchedEffect(biometricEnabled, unlocked) {
                    if (biometricEnabled && biometricAvailable && !unlocked) {
                        BiometricHelper.authenticate(
                            activity = this@MainActivity,
                            onSuccess = {
                                authErr = null
                                unlocked = true
                            },
                            onError = { msg -> authErr = msg },
                        )
                    }
                }

                if (!unlocked) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            Text("Antigravity Remote Locked", style = MaterialTheme.typography.titleMedium)
                            if (authErr != null) {
                                Text(authErr!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = {
                                    BiometricHelper.authenticate(
                                        activity = this@MainActivity,
                                        onSuccess = {
                                            authErr = null
                                            unlocked = true
                                        },
                                        onError = { msg -> authErr = msg },
                                    )
                                },
                            ) {
                                Text("Unlock")
                            }
                        }
                    }
                } else if (companionMode) {
                    val vm: AppViewModel = viewModel()
                    val uiState by vm.uiState.collectAsStateWithLifecycle()
                    val connState by vm.connectionState.collectAsStateWithLifecycle()

                    when (val screen = uiState.screen) {
                        AppScreen.LoginOrPair -> {
                            BackHandler {
                                if (uiState.hosts.isNotEmpty()) {
                                    vm.backToDevices()
                                } else {
                                    companionMode = false
                                }
                            }
                            LoginPairScreen(
                                canGoBack = true,
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                onBack = {
                                    if (uiState.hosts.isNotEmpty()) {
                                        vm.backToDevices()
                                    } else {
                                        companionMode = false
                                    }
                                },
                                onPairQr = vm::pairFromQr,
                                onPairManual = { url, token -> vm.pairManual(url, token) },
                                onLogin = vm::loginWithPassword,
                            )
                        }
                        AppScreen.DevicesAndSessions -> {
                            BackHandler { companionMode = false }
                            DevicesAndSessionsScreen(
                                hosts = uiState.hosts,
                                selectedHost = uiState.selectedHost,
                                sessions = uiState.sessions,
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                biometricAvailable = biometricAvailable,
                                biometricEnabled = uiState.biometricEnabled,
                                onSelectHost = vm::selectHost,
                                onAddHost = vm::openAddHost,
                                onRemoveHost = vm::removeAndRevokeHost,
                                onRefresh = vm::refreshSessions,
                                onCreateSession = vm::createAndOpenSession,
                                onOpenSession = vm::openTerminal,
                                onKillSession = vm::killSession,
                                onToggleBiometric = { enabled ->
                                    biometricEnabled = enabled
                                    vm.setBiometricEnabled(enabled)
                                },
                            )
                        }
                        is AppScreen.Terminal -> {
                            TerminalScreen(
                                host = screen.host,
                                session = screen.session,
                                connectionState = connState,
                                events = vm.terminalEvents,
                                quickCommands = uiState.quickCommands,
                                onBack = vm::backToDevices,
                                onSendInput = vm::sendTerminalInput,
                                onResize = vm::sendTerminalResize,
                                onReconnect = vm::reconnectTerminal,
                                onKillSession = { vm.killSession(screen.session.id) },
                                onAddQuickCommand = vm::addQuickCommand,
                                onRemoveQuickCommand = vm::removeQuickCommand,
                            )
                        }
                    }
                } else {
                    val webTitle = activeWebTitle
                    val webUrl = activeWebUrl
                    if (webTitle != null && webUrl != null) {
                        RemoteWebViewScreen(
                            title = webTitle,
                            url = webUrl,
                            onBack = {
                                activeWebTitle = null
                                activeWebUrl = null
                            },
                            onOpenInCustomTab = { targetUrl ->
                                activeWebTitle = null
                                activeWebUrl = null
                                launchDarkCustomTab(targetUrl)?.let { err -> errorMessage = err }
                            },
                        )
                    } else {
                        RemoteControlHomeScreen(
                            devices = devices,
                            errorMessage = errorMessage,
                            useCustomTabs = useCustomTabs,
                            biometricAvailable = biometricAvailable,
                            biometricEnabled = biometricEnabled,
                            onOpenUrl = { title, url -> openRemote(title, url) },
                            onAddFromQr = { rawQr ->
                                RemoteLinkParser.parse(rawQr)
                                    .onSuccess { (name, url) ->
                                        val dev = RemoteDevice(
                                            id = UUID.randomUUID().toString(),
                                            name = name,
                                            url = url,
                                        )
                                        devices = storage.upsertRemoteDevice(dev)
                                        openRemote(dev.name, dev.url)
                                    }
                                    .onFailure { err ->
                                        errorMessage = err.message ?: "Invalid QR code"
                                    }
                            },
                            onAddFromLink = { customName, rawUrl ->
                                RemoteLinkParser.parse(rawUrl, customName)
                                    .onSuccess { (name, url) ->
                                        val dev = RemoteDevice(
                                            id = UUID.randomUUID().toString(),
                                            name = name,
                                            url = url,
                                        )
                                        devices = storage.upsertRemoteDevice(dev)
                                        openRemote(dev.name, dev.url)
                                    }
                                    .onFailure { err ->
                                        errorMessage = err.message ?: "Invalid Remote Control link"
                                    }
                            },
                            onRemoveDevice = { id ->
                                devices = storage.removeRemoteDevice(id)
                            },
                            onToggleCustomTabs = { enabled ->
                                useCustomTabs = enabled
                                storage.setUseCustomTabs(enabled)
                            },
                            onToggleBiometric = { enabled ->
                                biometricEnabled = enabled
                                storage.setBiometricEnabled(enabled)
                            },
                            onOpenCompanionMode = {
                                errorMessage = null
                                companionMode = true
                            },
                        )
                    }
                }
            }
        }
    }

    private fun launchDarkCustomTab(url: String): String? {
        val darkColors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.parseColor("#0D1117"))
            .setNavigationBarColor(Color.parseColor("#0D1117"))
            .build()

        val customTabsIntent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(darkColors)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()

        return try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
            null
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                null
            } catch (_: ActivityNotFoundException) {
                "No browser app installed to open link"
            }
        }
    }
}
