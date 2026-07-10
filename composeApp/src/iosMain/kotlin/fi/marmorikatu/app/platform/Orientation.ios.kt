package fi.marmorikatu.app.platform

import androidx.compose.runtime.Composable

@Composable
actual fun LockLandscapeWhileVisible() {
    // No-op: iOS drives rotation from the device and the app's supported
    // orientations. Programmatic locking (UIViewController.requestGeometryUpdate)
    // is intrusive and iOS-version-specific, so the viewer simply uses whatever
    // orientation the user is holding.
}
