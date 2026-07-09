package fi.marmorikatu.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import fi.marmorikatu.app.shell.MarmorikatuApp
import fi.marmorikatu.app.shell.ShellViewModel
import fi.marmorikatu.app.theme.MarmorikatuTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The app root. The theme is dark-first — the design's daylight variant is a
 * deliberate toggle rather than a system-following default, because the shelf
 * tablet lives in a dim hallway.
 */
@Composable
fun App() {
    val shell: ShellViewModel = koinViewModel()
    val dark by shell.dark.collectAsState()

    MarmorikatuTheme(dark = dark) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            MarmorikatuApp(widthDp = maxWidth.value.toInt(), viewModel = shell)
        }
    }
}
