package fi.marmorikatu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import fi.marmorikatu.app.shell.UiSignals
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.background.AnnouncementService
import fi.marmorikatu.core.background.BackgroundMode
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            MicPermission.onResult(granted)
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            BackgroundMode.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() — it reads the Theme.App.Starting
        // attributes off the window before the activity's content is set.
        // The system/backport splash it shows is a brief icon flash only; the
        // brand moment with live connection status is MkSplash in App.kt.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        MicPermission.requestLauncher = { permission -> micLauncher.launch(permission) }
        BackgroundMode.requestLauncher = { permission -> notificationLauncher.launch(permission) }
        setContent { App() }
        // Route a notification tap that cold-started the app.
        handleNavIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A notification tap while the app is already running (singleTop).
        setIntent(intent)
        handleNavIntent(intent)
    }

    /**
     * Forward a tapped notification's target view (see [AnnouncementService.routeFor])
     * to the shell, then clear it so a later recreation doesn't re-navigate.
     */
    private fun handleNavIntent(intent: Intent?) {
        val route = intent?.getStringExtra(AnnouncementService.EXTRA_NAV) ?: return
        GlobalContext.getOrNull()?.getOrNull<UiSignals>()?.requestNav(route)
        intent.removeExtra(AnnouncementService.EXTRA_NAV)
    }

    override fun onDestroy() {
        MicPermission.requestLauncher = null
        BackgroundMode.requestLauncher = null
        super.onDestroy()
    }
}
