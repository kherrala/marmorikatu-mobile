package fi.marmorikatu.app

import androidx.compose.ui.window.ComposeUIViewController
import fi.marmorikatu.app.di.initKoin
import platform.UIKit.UIViewController

private var koinStarted = false

fun MainViewController(): UIViewController {
    if (!koinStarted) {
        initKoin()
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
