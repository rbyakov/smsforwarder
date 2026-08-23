package com.rbiakov.messageforwarder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rbiakov.messageforwarder.ui.theme.MessageforwarderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MessageforwarderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ForwarderScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private data class ScreenState(
    val smsGranted: Boolean,
    val phoneGranted: Boolean,
    val simState: SimState,
) {
    val allGranted: Boolean get() = smsGranted && phoneGranted
}

private fun readState(context: Context): ScreenState {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return ScreenState(
        smsGranted = granted(Manifest.permission.RECEIVE_SMS),
        phoneGranted = granted(Manifest.permission.READ_PHONE_STATE),
        simState = SimHelper.getSimState(context),
    )
}

@Composable
fun ForwarderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readState(context)) }

    // Re-read permissions and SIMs every time the screen resumes
    // (e.g. after returning from system settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = readState(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { state = readState(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        StatusLine(state)

        PermissionsBlock(state) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE),
            )
        }

        InfoBlock()
        SimBlock(state.simState)

        Button(
            onClick = {
                ForwardWorker.enqueueTest(context)
                Toast.makeText(context, context.getString(R.string.test_enqueued), Toast.LENGTH_SHORT).show()
            },
            // Can't send a test until the recipient / SMTP is configured.
            enabled = Config.isConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.send_test))
        }

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.disable_battery_optimization))
        }

        Text(
            stringResource(R.string.battery_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(state: ScreenState) {
    val (textRes, color) = when {
        !state.allGranted -> R.string.status_no_permissions to MaterialTheme.colorScheme.error
        !Config.isConfigured -> R.string.status_not_configured to MaterialTheme.colorScheme.error
        else -> R.string.status_running to Color(0xFF2E7D32)
    }
    Text(stringResource(textRes), style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun PermissionsBlock(state: ScreenState, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.titleSmall)
            PermissionRow(stringResource(R.string.permission_sms), state.smsGranted)
            PermissionRow(stringResource(R.string.permission_sim), state.phoneGranted)
            if (!state.allGranted) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.grant_permissions))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "✓" else "✗",
            color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun InfoBlock() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.info_forwarding_for, Config.targetSimSuffix))
            val recipient = Config.forwardTo.ifBlank { stringResource(R.string.info_not_set) }
            Text(stringResource(R.string.info_sending_to, recipient))
        }
    }
}

@Composable
private fun SimBlock(simState: SimState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.sim_title), style = MaterialTheme.typography.titleSmall)
            when {
                !simState.hasPermission ->
                    Text(stringResource(R.string.sim_no_access), color = MaterialTheme.colorScheme.error)
                simState.sims.isEmpty() ->
                    Text(stringResource(R.string.sim_none), color = MaterialTheme.colorScheme.error)
                else -> simState.sims.forEach { sim ->
                    val numberLabel = if (sim.number.isNotBlank()) {
                        "…${sim.number.takeLast(4)}"
                    } else {
                        stringResource(R.string.sim_number_unknown)
                    }
                    val carrier = sim.carrier.ifBlank { stringResource(R.string.sim_carrier_unknown) }
                    val role = stringResource(
                        if (sim.isTarget) R.string.sim_role_forwarded else R.string.sim_role_ignored,
                    )
                    Text(stringResource(R.string.sim_row, numberLabel, carrier, role))
                }
            }
            if (simState.hasPermission && !simState.targetFound) {
                Text(
                    stringResource(R.string.sim_target_not_found, Config.targetSimSuffix),
                    color = Color(0xFFB26A00),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
