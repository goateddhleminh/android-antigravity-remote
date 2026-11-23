package com.agyremote.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agyremote.app.model.ANTIGRAVITY_REMOTE_URL
import com.agyremote.app.model.RemoteDevice
import com.agyremote.app.ui.theme.TermAccentGreen
import com.agyremote.app.ui.theme.TermAccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlHomeScreen(
    devices: List<RemoteDevice>,
    errorMessage: String?,
    useCustomTabs: Boolean,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onOpenUrl: (title: String, url: String) -> Unit,
    onAddFromQr: (String) -> Unit,
    onAddFromLink: (name: String, url: String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onToggleCustomTabs: (Boolean) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onOpenCompanionMode: () -> Unit,
) {
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showPasteLinkDialog by rememberSaveable { mutableStateOf(false) }

    if (showQrScanner) {
        QrScannerDialog(
            onScanned = { raw ->
                showQrScanner = false
                onAddFromQr(raw)
            },
            onDismiss = { showQrScanner = false },
        )
    }

    if (showPasteLinkDialog) {
        PasteRemoteLinkDialog(
            onDismiss = { showPasteLinkDialog = false },
            onSave = { name, url ->
                showPasteLinkDialog = false
                onAddFromLink(name, url)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("Antigravity Remote")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Connect directly to Google Antigravity Remote Control — no PC server needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // Primary actions: Scan Antigravity Settings QR or Open Antigravity Remote directly
            item {
                Button(
                    onClick = { showQrScanner = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan Antigravity Remote QR Code")
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { onOpenUrl("Antigravity Remote", ANTIGRAVITY_REMOTE_URL) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Open Dashboard", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showPasteLinkDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Paste Link", maxLines = 1)
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onOpenCompanionMode,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("PC Companion Terminal (PTY)")
                }
            }

            // Saved PCs section
            item {
                Text(
                    "Saved PCs (${devices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (devices.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("No PCs saved yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "On your PC, open Antigravity → Settings → Remote Control → Enable Remote Control, then tap \"Scan Antigravity Remote QR Code\" above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(devices, key = { it.id }) { device ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(device.name, device.url) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                tint = TermAccentGreen,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    device.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onRemoveDevice(device.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TermAccentRed)
                            }
                        }
                    }
                }
            }

            // Settings card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Chrome Custom Tab Mode", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Recommended for Google Sign-In & Passkeys. Turn off to open inside In-App WebView.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = useCustomTabs, onCheckedChange = onToggleCustomTabs)
                        }

                        if (biometricAvailable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text("Biometric App Lock", style = MaterialTheme.typography.bodyMedium)
                                }
                                Switch(checked = biometricEnabled, onCheckedChange = onToggleBiometric)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteRemoteLinkDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Remote Control Link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("PC Name (optional)") },
                    placeholder = { Text("e.g. admin-pc-rising-ember") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Remote Control Link") },
                    placeholder = { Text("https://antigravity.google.com/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = url.isNotBlank(),
                onClick = { onSave(name, url) },
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
