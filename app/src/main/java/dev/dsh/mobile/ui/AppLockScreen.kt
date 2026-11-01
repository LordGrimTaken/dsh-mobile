package dev.dsh.mobile.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen lock shown when the optional app lock is enabled.
 *
 * Uses the system device-credential confirmation (PIN/pattern/password, with biometrics when the
 * OEM includes them) so it works on every supported API level and from a plain ComponentActivity.
 */
@Composable
fun AppLockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val secure = keyguard?.isDeviceSecure == true
    var error by remember { mutableStateOf<String?>(null) }
    var prompted by remember { mutableStateOf(false) }

    val confirm = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onUnlock()
        } else {
            error = "Unlock cancelled"
        }
    }

    val authenticate: () -> Unit = {
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            "Unlock DSH Mobile",
            "Confirm your device PIN, pattern, or biometrics",
        )
        if (intent == null) {
            onUnlock()
        } else {
            runCatching { confirm.launch(intent) }
                .onFailure { error = "Couldn't open the device unlock prompt" }
        }
    }

    LaunchedEffect(Unit) {
        if (secure && !prompted) {
            prompted = true
            authenticate()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "❯",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "DSH MOBILE",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Locked",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            if (secure) {
                "Confirm your device PIN, pattern, password, or biometrics to continue."
            } else {
                "No secure screen lock is set on this device, so the app lock can't be " +
                    "enforced. Set a PIN or pattern in Android settings first."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        HadalPrimaryButton(
            text = if (secure) "Unlock" else "Continue",
            onClick = { if (secure) authenticate() else onUnlock() },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { message ->
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
