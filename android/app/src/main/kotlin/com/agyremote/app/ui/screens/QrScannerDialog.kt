package com.agyremote.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Scan PC Pairing QR", style = MaterialTheme.typography.titleMedium)

                if (!hasPermission) {
                    Text(
                        "Camera permission is required to scan the QR code shown by the PC Companion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Camera Permission")
                    }
                } else {
                    val scannedOnce = remember { AtomicBoolean(false) }
                    val disposed = remember { AtomicBoolean(false) }
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    val scanner = remember { BarcodeScanning.getClient() }
                    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

                    DisposableEffect(Unit) {
                        onDispose {
                            disposed.set(true)
                            cameraProviderRef?.unbindAll()
                            cameraProviderRef = null
                            scanner.close()
                            executor.shutdown()
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                                providerFuture.addListener(
                                    {
                                        if (disposed.get()) return@addListener
                                        val provider = providerFuture.get()
                                        cameraProviderRef = provider
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                        analysis.setAnalyzer(executor) { imageProxy ->
                                            val mediaImage = imageProxy.image
                                            if (mediaImage != null && !scannedOnce.get()) {
                                                val input = InputImage.fromMediaImage(
                                                    mediaImage,
                                                    imageProxy.imageInfo.rotationDegrees,
                                                )
                                                scanner.process(input)
                                                    .addOnSuccessListener { barcodes ->
                                                        val raw = barcodes.firstOrNull()?.rawValue
                                                        if (!raw.isNullOrBlank() && scannedOnce.compareAndSet(false, true)) {
                                                            previewView.post { onScanned(raw) }
                                                        }
                                                    }
                                                    .addOnCompleteListener { imageProxy.close() }
                                            } else {
                                                imageProxy.close()
                                            }
                                        }
                                        runCatching {
                                            provider.unbindAll()
                                            provider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                analysis,
                                            )
                                        }
                                    },
                                    ContextCompat.getMainExecutor(ctx),
                                )
                                previewView
                            },
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}
