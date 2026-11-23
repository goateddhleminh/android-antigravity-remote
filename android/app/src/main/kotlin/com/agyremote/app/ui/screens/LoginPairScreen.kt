package com.agyremote.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPairScreen(
    canGoBack: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onPairQr: (String) -> Unit,
    onPairManual: (url: String, token: String) -> Unit,
    onLogin: (url: String, email: String, password: String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }

    var serverUrl by rememberSaveable { mutableStateOf("https://my-pc.tailnet.ts.net") }
    var pairToken by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("admin@local") }
    var password by rememberSaveable { mutableStateOf("") }

    if (showQrScanner) {
        QrScannerDialog(
            onScanned = { raw ->
                showQrScanner = false
                onPairQr(raw)
            },
            onDismiss = { showQrScanner = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Antigravity Remote")
                    }
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Connect to your PC Companion over Tailscale TLS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Pair PC (QR)") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Email Login") })
            }

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
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

            if (selectedTab == 0) {
                Button(
                    onClick = { showQrScanner = true },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan QR Code on PC")
                }

                Text(
                    "Or enter your Tailscale HTTPS URL & pairing token manually:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("PC Companion URL (HTTPS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                OutlinedTextField(
                    value = pairToken,
                    onValueChange = { pairToken = it },
                    label = { Text("Pairing Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { onPairManual(serverUrl, pairToken) },
                    enabled = !isLoading && serverUrl.isNotBlank() && pairToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Pair with Token")
                    }
                }
            } else {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("PC Companion URL (HTTPS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { onLogin(serverUrl, email, password) },
                    enabled = !isLoading && serverUrl.isNotBlank() && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Sign In")
                    }
                }
            }
        }
    }
}
