package ai.openclaw.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import ai.openclaw.wear.ui.AgentDialScreen
import ai.openclaw.wear.ui.ConnectingScreen
import ai.openclaw.wear.ui.WearTheme

class WearMainActivity : ComponentActivity() {
    private val viewModel: WearViewModel by viewModels()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request mic permission up front
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.onMicPermissionResult(true)
        }

        setContent {
            WearTheme {
                val screen by viewModel.screen.collectAsState()
                when (screen) {
                    WearScreen.Connecting -> ConnectingScreen(viewModel)
                    WearScreen.Dial -> AgentDialScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val bridge = (application as WearApp).phoneBridge
        bridge.startListening()
        viewModel.connect()
    }

    override fun onPause() {
        (application as WearApp).phoneBridge.stopListening()
        super.onPause()
    }
}
