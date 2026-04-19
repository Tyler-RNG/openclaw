package ai.openclaw.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
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

    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the display on while the app is foregrounded so the omnitrix
        // stays visible without the user tapping to wake. Wear OS releases
        // the flag automatically when the activity is paused/backgrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request mic permission up front
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.onMicPermissionResult(true)
        }

        // Request notifications on Android 13+; silently accept denial — the
        // app still works, user just won't see the per-agent reply notification.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
