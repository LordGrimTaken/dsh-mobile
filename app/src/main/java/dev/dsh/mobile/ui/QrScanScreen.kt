package dev.dsh.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.PairQrParseResult
import dev.dsh.mobile.data.PairQrPayloadParser
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner for `dshmobile://pair?...` payloads.
 *
 * The host's "Connect a phone" page shows the QR; scanning it opens the pairing screen with the
 * URL/code/label already filled in and pairs immediately. The manual six-digit flow stays one
 * back-press away, so a broken camera never blocks pairing.
 */
@Composable
fun QrScanScreen(state: AppState) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(granted) }
    var denied by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        denied = !allowed
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraPreview(
                onCameraError = { cameraError = true },
                onBarcode = { raw ->
                    if (handled) return@CameraPreview
                    when (val result = PairQrPayloadParser.parse(raw)) {
                        is PairQrParseResult.Valid -> {
                            handled = true
                            state.handlePairQrPayload(result.payload, autoPair = true)
                        }

                        is PairQrParseResult.Invalid -> {
                            scanError = result.message
                        }
                    }
                },
            )
            ScannerOverlay()
        } else {
            CameraPermissionBody(
                denied = denied && askedOnce,
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = state::closeQrScanner) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                "Scan pairing QR",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                scanError
                    ?: if (cameraError) {
                        "Camera unavailable on this device — go back and enter the host URL and " +
                            "6-digit code manually."
                    } else {
                        "Point the camera at the QR code on the host's “Connect a phone” page. " +
                            "Codes are single use and expire after 5 minutes."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (scanError != null) Color(0xFFFFB4AB) else Color.White,
                textAlign = TextAlign.Center,
            )
            if (scanError != null) {
                TextButton(onClick = { scanError = null }) {
                    Text("Keep scanning", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Centered square reticle with corner accents over the camera preview. */
@Composable
private fun ScannerOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(280.dp)
                .border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(28.dp),
                ),
        )
    }
}

@Composable
private fun CameraPermissionBody(denied: Boolean, onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.QrCodeScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (denied) "Camera access is off" else "Camera access is needed",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (denied) {
                "Allow the camera for DSH Mobile in Android settings, or go back and enter the " +
                    "6-digit code manually."
            } else {
                "The scanner only reads the pairing QR code; nothing is recorded or uploaded."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB7C7C0),
            textAlign = TextAlign.Center,
        )
        if (!denied) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Allow camera")
            }
        }
    }
}

@Composable
private fun CameraPreview(onCameraError: () -> Unit, onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val latestBarcode by rememberUpdatedState(onBarcode)
    val analyzer = remember { QrAnalyzer { raw -> latestBarcode(raw) } }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener(
            {
                val bound = runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
                if (bound.isFailure) onCameraError()
            },
            mainExecutor,
        )
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            analyzer.close()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/** ML Kit QR analyzer: one in-flight frame at a time, latest frame wins. */
@OptIn(ExperimentalGetImage::class)
private class QrAnalyzer(private val onBarcode: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    private val busy = AtomicBoolean(false)

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.rawValue != null }?.rawValue?.let(onBarcode)
            }
            .addOnCompleteListener {
                busy.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        runCatching { scanner.close() }
    }
}
