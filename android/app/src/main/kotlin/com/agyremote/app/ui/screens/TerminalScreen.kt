package com.agyremote.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.agyremote.app.model.AppJson
import com.agyremote.app.model.ConnectionState
import com.agyremote.app.model.PairedHost
import com.agyremote.app.model.QuickCommand
import com.agyremote.app.model.SessionInfo
import com.agyremote.app.network.TerminalEvent
import com.agyremote.app.ui.theme.TermAccentAmber
import com.agyremote.app.ui.theme.TermAccentBlue
import com.agyremote.app.ui.theme.TermAccentGreen
import com.agyremote.app.ui.theme.TermAccentRed
import com.agyremote.app.ui.theme.TermBackground
import com.agyremote.app.ui.theme.TermSurface
import com.agyremote.app.ui.theme.TermSurfaceElevated
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(
    host: PairedHost,
    session: SessionInfo,
    connectionState: ConnectionState,
    events: SharedFlow<TerminalEvent>,
    quickCommands: List<QuickCommand>,
    onBack: () -> Unit,
    onSendInput: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    onReconnect: () -> Unit,
    onKillSession: () -> Unit,
    onAddQuickCommand: (label: String, command: String) -> Unit,
    onRemoveQuickCommand: (String) -> Unit,
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var termReady by remember { mutableStateOf(false) }
    val pendingOutput = remember { mutableListOf<String>() }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableIntStateOf(13) }
    var showAddCmdDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    fun sendWithModifiers(raw: String) {
        val transformed = when {
            ctrlArmed && raw.length == 1 -> {
                ctrlArmed = false
                val upper = raw[0].uppercaseChar()
                if (upper in 'A'..'Z') (upper.code - 64).toChar().toString() else raw
            }
            altArmed -> {
                altArmed = false
                "\u001B$raw"
            }
            else -> raw
        }
        onSendInput(transformed)
    }

    fun writeToXterm(data: String) {
        if (data.isEmpty()) return
        val wv = webViewRef
        if (!termReady || wv == null) {
            pendingOutput.add(data)
            return
        }
        val b64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        wv.post {
            wv.evaluateJavascript("window.agyTerm && window.agyTerm.writeBase64('$b64');", null)
        }
    }

    fun flushPendingOutput() {
        if (pendingOutput.isEmpty()) return
        val combined = pendingOutput.joinToString("")
        pendingOutput.clear()
        writeToXterm(combined)
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is TerminalEvent.Output -> writeToXterm(event.data)
                is TerminalEvent.Attached -> writeToXterm(event.replay)
                is TerminalEvent.SessionExit -> writeToXterm("\r\n\u001B[33m[Process exited with code ${event.exitCode}]\u001B[0m\r\n")
                is TerminalEvent.ServerError -> writeToXterm("\r\n\u001B[31m[Error ${event.code}: ${event.message}]\u001B[0m\r\n")
            }
        }
    }

    if (showAddCmdDialog) {
        AddQuickCommandDialog(
            onDismiss = { showAddCmdDialog = false },
            onAdd = { label, cmd ->
                showAddCmdDialog = false
                onAddQuickCommand(label, cmd)
            },
        )
    }

    val statusColor = when (connectionState) {
        is ConnectionState.Connected -> TermAccentGreen
        is ConnectionState.Connecting, is ConnectionState.Reconnecting -> TermAccentAmber
        is ConnectionState.AuthError, ConnectionState.Disconnected -> TermAccentRed
    }
    val statusLabel = when (connectionState) {
        is ConnectionState.Connected -> "Live"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Reconnecting -> "Retry #${connectionState.attempt}"
        is ConnectionState.AuthError -> "Auth Error"
        ConnectionState.Disconnected -> "Offline"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                            Text(
                                "${host.name} • ${session.name}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Detach")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            fontSize = (fontSize - 1).coerceAtLeast(9)
                            webViewRef?.evaluateJavascript("window.agyTerm && window.agyTerm.setFontSize($fontSize);", null)
                        },
                    ) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "Smaller text")
                    }
                    IconButton(
                        onClick = {
                            fontSize = (fontSize + 1).coerceAtMost(24)
                            webViewRef?.evaluateJavascript("window.agyTerm && window.agyTerm.setFontSize($fontSize);", null)
                        },
                    ) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "Larger text")
                    }
                    IconButton(onClick = onReconnect) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                    }
                    IconButton(onClick = onKillSession) {
                        Icon(Icons.Default.Close, contentDescription = "Kill session", tint = TermAccentRed)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TermSurface),
            )
        },
        containerColor = TermBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Hardened Offline xterm.js WebView
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .build()

                    WebView(ctx).apply {
                        setBackgroundColor(0xFF0D1117.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onInput(data: String) {
                                    post { sendWithModifiers(data) }
                                }

                                @JavascriptInterface
                                fun onResize(cols: Int, rows: Int) {
                                    post { onResize(cols, rows) }
                                }

                                @JavascriptInterface
                                fun onReady(cols: Int, rows: Int) {
                                    post {
                                        termReady = true
                                        flushPendingOutput()
                                        onResize(cols, rows)
                                    }
                                }
                            },
                            "AndroidTerminal",
                        )

                        loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
                        webViewRef = this
                    }
                },
            )

            DisposableEffect(Unit) {
                onDispose {
                    webViewRef?.destroy()
                    webViewRef = null
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Quick-command bar (saved Antigravity snippets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickCommands.forEach { qc ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TermSurfaceElevated,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                val suffix = if (qc.appendNewline) "\r" else ""
                                onSendInput(qc.command + suffix)
                            },
                            onLongClick = { onRemoveQuickCommand(qc.id) },
                        ),
                    ) {
                        Text(
                            qc.label,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TermAccentBlue,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TermSurfaceElevated,
                    modifier = Modifier.combinedClickable(onClick = { showAddCmdDialog = true }),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add quick command", modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Extra key bar: Esc, Tab, Shift+Tab, Ctrl, Alt, Arrows, Ctrl+C, Copy, Paste
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF11161D))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtraKey("ESC") { onSendInput("\u001B") }
                ExtraKey("TAB") { onSendInput("\t") }
                ExtraKey("S-TAB") { onSendInput("\u001B[Z") } // Cycles agy execution modes
                ExtraKey("CTRL", active = ctrlArmed) { ctrlArmed = !ctrlArmed }
                ExtraKey("ALT", active = altArmed) { altArmed = !altArmed }
                ExtraKey("^C", tint = TermAccentRed) { onSendInput("\u0003") }
                ExtraKey("←") { onSendInput("\u001B[D") }
                ExtraKey("↓") { onSendInput("\u001B[B") }
                ExtraKey("↑") { onSendInput("\u001B[A") }
                ExtraKey("→") { onSendInput("\u001B[C") }

                // Copy selection from xterm.js
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TermSurfaceElevated,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            webViewRef?.evaluateJavascript("window.agyTerm ? window.agyTerm.getSelection() : ''") { raw ->
                                val cleaned = runCatching {
                                    if (raw.isNullOrBlank() || raw == "null") "" else AppJson.decodeFromString<String>(raw)
                                }.getOrDefault("")
                                if (cleaned.isNotEmpty()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("terminal", cleaned))
                                }
                            }
                        },
                    ),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .size(16.dp),
                    )
                }

                // Paste clipboard into PTY
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TermSurfaceElevated,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clip.isNullOrEmpty()) {
                                onSendInput(clip)
                            }
                        },
                    ),
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKey(
    label: String,
    active: Boolean = false,
    tint: Color = Color(0xFFE6EDF3),
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) TermAccentBlue else TermSurfaceElevated,
        modifier = Modifier.combinedClickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (active) Color(0xFF0D1117) else tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun AddQuickCommandDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, command: String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Quick Command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Button Label") },
                    placeholder = { Text("e.g. Run Tests") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Command") },
                    placeholder = { Text("e.g. agy -p \"run tests\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = label.isNotBlank() && command.isNotBlank(),
                onClick = { onAdd(label, command) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
