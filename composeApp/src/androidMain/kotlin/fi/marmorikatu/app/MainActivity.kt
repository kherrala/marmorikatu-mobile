package fi.marmorikatu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.background.BackgroundMode

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
        super.onCreate(savedInstanceState)
        MicPermission.requestLauncher = { permission -> micLauncher.launch(permission) }
        BackgroundMode.requestLauncher = { permission -> notificationLauncher.launch(permission) }
        setContent { App() }
    }

    override fun onDestroy() {
        MicPermission.requestLauncher = null
        BackgroundMode.requestLauncher = null
        super.onDestroy()
    }
}
