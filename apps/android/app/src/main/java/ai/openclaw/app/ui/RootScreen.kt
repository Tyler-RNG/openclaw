package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.ui.design.LocalSpriteAvatarStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** Chooses onboarding or the authenticated app shell from persisted app state. */
@Composable
fun RootScreen(viewModel: MainViewModel) {
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()

  if (!onboardingCompleted) {
    OnboardingFlow(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    return
  }

  // Published once here so agent avatar surfaces anywhere in the shell can
  // render animated SpriteCore avatars without threading the store through.
  val spriteAvatarStore by viewModel.spriteAvatarStore.collectAsState()
  CompositionLocalProvider(LocalSpriteAvatarStore provides spriteAvatarStore) {
    ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
  }
}
