package fi.marmorikatu.app.platform

import androidx.compose.runtime.Composable

/**
 * While this composable is in the composition, request landscape orientation and
 * restore the previous setting when it leaves. Used by the full-screen camera
 * viewer so a wide 16:9 still fills the screen instead of a thin portrait strip.
 *
 * Android honours it via the activity's requested orientation; on iOS it is a
 * no-op (rotation there is driven by the device and the app's supported
 * orientations, and programmatic locking is intrusive and version-specific).
 */
@Composable
expect fun LockLandscapeWhileVisible()
