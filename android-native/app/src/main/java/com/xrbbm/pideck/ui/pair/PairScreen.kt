package com.xrbbm.pideck.ui.pair

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xrbbm.pideck.core.PairingParser
import java.util.concurrent.Executors

/**
 * Pairing screen: CameraX + ML Kit QR scan with manual address fallback
 * (mirrors Flutter PairPage for emulators / camera-less devices).
 */
@Composable
fun PairScreen(onPaired: (String) -> Unit) {
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun accept(raw: String) {
        if (done) return
        val baseUrl = PairingParser.parseBaseUrl(raw)
        if (baseUrl == null) {
            error = "二维码内容不是有效的服务地址：$raw"
            return
        }
        done = true
        onPaired(baseUrl)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("配对 PiDeck", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "将取景框对准 PiDeck 设置页的二维码，或手动输入地址",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            CameraPermissionGate(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f),
            ) {
                QrScannerView(
                    modifier = Modifier.fillMaxSize(),
                    onDetect = ::accept,
                )
            }
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("http://192.168.50.210:8765") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { accept(manual) }) { Text("连接") }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Camera permission gate: MIUI denies silently on bind without a grant,
 * so never reach CameraX ungranted (black preview, no system dialog).
 * States: first run -> ask; denied -> retry text; permanently denied ->
 * jump-to-settings button. Manual address entry below is unaffected.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val camera = rememberPermissionState(android.Manifest.permission.CAMERA)
    LaunchedEffect(Unit) {
        if (!camera.status.isGranted && !camera.status.shouldShowRationale) {
            camera.launchPermissionRequest()
        }
    }
    when {
        camera.status.isGranted -> Box(modifier = modifier) { content() }
        camera.status.shouldShowRationale -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限来扫码配对")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { camera.launchPermissionRequest() }) { Text("允许相机权限") }
            }
        }
        else -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("相机权限被拒绝，扫码不可用，可手动输入地址")
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("去设置里打开") }
            }
        }
    }
}

@Composable
private fun QrScannerView(modifier: Modifier = Modifier, onDetect: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val options = BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    val scanner = BarcodeScanning.getClient(options)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    barcodes.firstOrNull()?.rawValue?.let(onDetect)
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycle,
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
