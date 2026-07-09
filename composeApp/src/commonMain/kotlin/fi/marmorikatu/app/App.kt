package fi.marmorikatu.app

import androidx.compose.runtime.Composable
import fi.marmorikatu.app.debug.DebugScreen
import fi.marmorikatu.app.theme.MarmorikatuTheme

@Composable
fun App() {
    MarmorikatuTheme {
        DebugScreen()
    }
}
