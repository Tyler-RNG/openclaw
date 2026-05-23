package ai.openclaw.glasses.ui

import ai.openclaw.glasses.GlassesViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GlassesScreen(viewModel: GlassesViewModel) {
    val screen by viewModel.screen.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "OpenClaw Glasses", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
            Text(
                text = when (val s = screen) {
                    GlassesViewModel.Screen.Idle -> "Tap scan to find your Frame"
                    GlassesViewModel.Screen.Scanning -> "Scanning…"
                    is GlassesViewModel.Screen.Connecting -> "Connecting to ${s.name ?: "Frame"}…"
                    is GlassesViewModel.Screen.Connected -> "Connected to ${s.name ?: "Frame"}"
                    is GlassesViewModel.Screen.Error -> "Error: ${s.message}"
                },
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = { viewModel.startScan() },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Scan")
            }
        }
    }
}
