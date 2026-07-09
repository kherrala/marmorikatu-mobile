package fi.marmorikatu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import fi.marmorikatu.core.audio.MicPermission

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            MicPermission.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MicPermission.requestLauncher = { permission -> permissionLauncher.launch(permission) }
        setContent { App() }
    }

    override fun onDestroy() {
        MicPermission.requestLauncher = null
        super.onDestroy()
    }
}
