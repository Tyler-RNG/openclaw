package ai.openclaw.glasses

import ai.openclaw.glasses.ui.GlassesScreen
import ai.openclaw.glasses.ui.GlassesTheme
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class GlassesMainActivity : ComponentActivity() {
    private val viewModel: GlassesViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* ignored: UI reflects state on next user action */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            GlassesTheme {
                GlassesScreen(viewModel)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }
}
