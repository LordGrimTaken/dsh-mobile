package dev.dsh.mobile.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.ui.theme.Danger
import kotlinx.coroutines.launch

/**
 * Manual pairing for any host (desktop or cloud/headless): label + base URL + one-use 6-digit
 * code + device name -> POST /mobile/v1/pair/complete. Only base URL + token are assumed; the
 * same screen pairs additional hosts from Settings → Hosts.
 */
@Composable
fun PairingScreen(state: AppState) {
    val qrPrefill = state.pairingQrPrefill
    var label by remember(state.pairingQrRevision) {
        mutableStateOf(
            qrPrefill?.hostLabel?.takeIf { it.isNotBlank() } ?: state.pairingLabelDefault,
        )
    }
    var baseUrl by remember(state.pairingQrRevision) {
        mutableStateOf(qrPrefill?.baseUrl ?: "https://")
    }
    var code by remember(state.pairingQrRevision) {
        mutableStateOf(qrPrefill?.code.orEmpty())
    }
    var deviceName by remember { mutableStateOf(Build.MODEL.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val busy = state.pairingBusy
    val error = state.pairingError
    val cloudMode = label.trim().equals("Cloud", ignoreCase = true) ||
        state.pairingLabelDefault.equals("Cloud", ignoreCase = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = state::closePairing, enabled = !busy) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                if (cloudMode) "Connect cloud host" else "Pair a host",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                if (cloudMode) "Connect to a cloud host" else "Connect a DSH host",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (cloudMode) {
                    "Enter the HTTPS base URL of your headless DSH host and the 6-digit pairing " +
                        "code it issues. No Tailscale or desktop setup is required - the host just " +
                        "has to be reachable from this phone."
                } else {
                    "On the host, create a pairing code (Desktop: the DSH mobile helper; cloud: " +
                        "the host's admin flow). Then enter the host URL and the 6-digit code. " +
                        "Codes are single use and expire after 5 minutes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = state::openQrScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code")
            }
            Text(
                "Scan the code on the host's “Connect a phone” page, or enter the details below. " +
                    "The manual code path always works if the camera is unavailable.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (qrPrefill != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (busy) "QR scanned — connecting…" else "QR scanned — details filled in below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host label") },
                placeholder = { Text("Desktop / Cloud") },
                singleLine = true,
                enabled = !busy,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host URL") },
                placeholder = { Text("https://dsh.example.com") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                leadingIcon = {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dev host: http://10.0.2.2:3543",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { baseUrl = "http://10.0.2.2:3543" }, enabled = !busy) {
                    Text("Use it")
                }
            }

            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isDigit() }.take(6)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("6-digit code") },
                placeholder = { Text("482913") },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Device name") },
                placeholder = { Text("Pixel 9") },
                singleLine = true,
                enabled = !busy,
            )

            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                    )
                }
            }

            HadalGhostButton(
                text = if (testing) "Testing…" else "Test host URL",
                onClick = {
                    if (testing) return@HadalGhostButton
                    testing = true
                    testMessage = null
                    testOk = null
                    scope.launch {
                        val result = state.testHostUrl(baseUrl)
                        result.fold(
                            onSuccess = { health ->
                                testOk = health.ok
                                testMessage = if (health.ok) {
                                    "reachable · host v${health.version.ifBlank { "?" }}"
                                } else {
                                    "host answered but reported unhealthy"
                                }
                            },
                            onFailure = { failure ->
                                testOk = false
                                testMessage = "unreachable: ${failure.message ?: failure::class.simpleName}"
                            },
                        )
                        testing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            testMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testOk == false) Danger else MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = { state.pair(baseUrl, code, deviceName, label) },                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !busy,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Pairing...")
                } else {
                    Text("Pair host")
                }
            }

            Text(
                "The device token is encrypted with the Android Keystore and stored only on this " +
                    "phone. Manage or remove hosts later in Settings → Hosts.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
