package com.agyremote.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.agyremote.app.ui.theme.TermBackground
import com.agyremote.app.ui.theme.TermSurface

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RemoteWebViewScreen(
    title: String,
    url: String,
    onBack: () -> Unit,
    onOpenInCustomTab: (String) -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(url) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        pendingFileCallback = null
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { onOpenInCustomTab(currentUrl) }) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Chrome Tab")
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
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF0D1117.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.setSupportMultipleWindows(false)
                        // Strip "; wv" token so modern web apps render full mobile UI
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url.toString()
                                if (request.isForMainFrame) {
                                    currentUrl = target
                                }
                                // Google OAuth pages block WebViews; hand off to Chrome Custom Tab automatically
                                if (request.url.host?.contains("accounts.google.com") == true) {
                                    onOpenInCustomTab(target)
                                    return true
                                }
                                return false
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                pendingFileCallback?.onReceiveValue(null)
                                pendingFileCallback = filePathCallback
                                filePickerLauncher.launch(arrayOf("*/*"))
                                return true
                            }
                        }

                        loadUrl(url)
                        webViewRef = this
                    }
                },
            )

            DisposableEffect(Unit) {
                onDispose {
                    pendingFileCallback?.onReceiveValue(null)
                    webViewRef?.destroy()
                    webViewRef = null
                }
            }
        }
    }
}
