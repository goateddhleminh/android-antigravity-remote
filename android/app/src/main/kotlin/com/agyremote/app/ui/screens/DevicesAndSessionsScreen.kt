package com.agyremote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.SessionInfo
import com.agyremote.app.ui.theme.TermAccentGreen
import com.agyremote.app.ui.theme.TermAccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesAndSessionsScreen(
    hosts: List<PairedHost>,
    selectedHost: PairedHost?,
    sessions: List<SessionInfo>,
    isLoading: Boolean,
    errorMessage: String?,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onSelectHost: (String) -> Unit,
    onAddHost: () -> Unit,
    onRemoveHost: (PairedHost) -> Unit,
    onRefresh: () -> Unit,
    onCreateSession: (name: String?, cwd: String?, command: String?) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onKillSession: (String) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
) {
    var showNewSessionDialog by rememberSaveable { mutableStateOf(false) }

    if (showNewSessionDialog) {
        NewSessionDialog(
            onDismiss = { showNewSessionDialog = false },
            onCreate = { name, cwd, cmd ->
                showNewSessionDialog = false
                onCreateSession(name, cwd, cmd)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My PCs & Sessions") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onAddHost) {
                        Icon(Icons.Default.Add, contentDescription = "Pair new PC")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (selectedHost != null) {
                FloatingActionButton(
                    onClick = { showNewSessionDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New session")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Paired PC selector row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    FilterChip(
                        selected = host.id == selectedHost?.id,
                        onClick = { onSelectHost(host.id) },
                        label = { Text(host.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }

            // Selected PC summary + security controls
            if (selectedHost != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(selectedHost.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    selectedHost.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onRemoveHost(selectedHost) }) {
                                Text("Revoke", color = TermAccentRed)
                            }
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
                                    Text("Biometric App Lock", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = biometricEnabled, onCheckedChange = onToggleBiometric)
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Text(
                "Terminal Sessions (${sessions.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )

            if (sessions.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text("No active terminal sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { showNewSessionDialog = true }) {
                            Text("Start New Session")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onOpen = { onOpenSession(session) },
                            onKill = { onKillSession(session.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    onOpen: () -> Unit,
    onKill: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (session.alive) TermAccentGreen else TermAccentRed),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    session.cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${session.cols}x${session.rows} • ${if (session.alive) "running" else "exited (${session.exitCode})"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onKill) {
                Icon(Icons.Default.Delete, contentDescription = "Kill session", tint = TermAccentRed)
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String?, cwd: String?, command: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var cwd by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("agy") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Terminal Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = command == "agy",
                        onClick = { command = "agy" },
                        label = { Text("agy") },
                    )
                    FilterChip(
                        selected = command == "agy --mode=plan",
                        onClick = { command = "agy --mode=plan" },
                        label = { Text("agy plan") },
                    )
                    FilterChip(
                        selected = command.isBlank(),
                        onClick = { command = "" },
                        label = { Text("Shell") },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("Project Folder (optional)") },
                    placeholder = { Text("e.g. C:\\Projects\\my-app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Startup Command (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.ifBlank { null },
                        cwd.ifBlank { null },
                        command.ifBlank { null },
                    )
                },
            ) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
