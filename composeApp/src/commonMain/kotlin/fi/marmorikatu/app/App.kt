package fi.marmorikatu.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import fi.marmorikatu.app.components.MkSplash
import fi.marmorikatu.app.components.MkSplashTask
import fi.marmorikatu.app.components.SplashTaskState
import fi.marmorikatu.app.shell.MarmorikatuApp
import fi.marmorikatu.app.shell.ShellViewModel
import fi.marmorikatu.app.shell.StartupProgress
import fi.marmorikatu.app.shell.StartupState
import fi.marmorikatu.app.theme.MarmorikatuTheme
import fi.marmorikatu.core.lifecycle.ConnectionManager
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** Below this width there is no room for the nav-rail kiosk layout. */
private const val TABLET_MIN_DP = 720f

/**
 * The effective dp width the kiosk is scaled toward. A large tablet (e.g. the
 * iPad at ~1080 dp landscape) renders at full native point resolution, which
 * reads small from across a hallway; scaling the whole UI so it behaves like a
 * ~760 dp canvas enlarges the rail and every readout for at-a-distance viewing.
 */
private const val KIOSK_TARGET_DP = 760f

/**
 * The boot splash always stays up at least this long, so a near-instant LAN
 * reconnect can't flash the brand moment on and off — it reads as a deliberate
 * launch screen, not a glitch, and gives the startup checklist a beat to be read.
 */
private const val SPLASH_MIN_TOTAL_MS = 2_000L

/**
 * How long the boot splash waits before offering a manual way past it. A
 * device that is away from the home network/VPN retries MQTT forever with
 * backoff (see `ConnectionManager.mqttLoop`) — without this the splash would
 * block the app indefinitely instead of degrading gracefully.
 */
private const val SPLASH_MANUAL_DISMISS_GRACE_MS = 4_000L

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
        // The shell is composed immediately, underneath the boot splash below —
        // not gated behind it — so `MarmorikatuApp`'s LaunchedEffect(Unit) {
        // viewModel.start() } kicks the real connections off right away instead
        // of waiting for the splash to dismiss.
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val rawW = maxWidth.value
                val rawH = maxHeight.value

                // Only the large-canvas kiosk is scaled up; phones (portrait and
                // landscape) stay 1:1. Scaling raises the density so the same dp
                // render as more pixels — a whole-UI zoom — and shrinks the dp space
                // the layout sees, so pass the scaled dimensions through.
                val kiosk = rawW >= TABLET_MIN_DP || rawW > rawH
                val scale = if (kiosk) (rawW / KIOSK_TARGET_DP).coerceIn(1f, 1.6f) else 1f

                if (scale > 1.01f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density * scale, d.fontScale),
                    ) {
                        MarmorikatuApp(
                            widthDp = (rawW / scale).toInt(),
                            heightDp = (rawH / scale).toInt(),
                            viewModel = shell,
                        )
                    }
                } else {
                    MarmorikatuApp(
                        widthDp = rawW.toInt(),
                        heightDp = rawH.toInt(),
                        viewModel = shell,
                    )
                }
            }

            BootSplash()
        }
    }
}

/**
 * Opaque overlay shown over the shell until the real house connection is up.
 * Driven by [ConnectionManager.mqttState] (injected directly — this is the
 * one piece of shell wiring `App.kt` owns) rather than a fixed timer, so a
 * broken home network/VPN shows as broken instead of a silent stall. Once
 * the grace period elapses, or the connection attempt has already failed, a
 * manual dismiss appears so the app never traps the user behind it.
 */
@Composable
private fun BootSplash() {
    val connections: ConnectionManager = koinInject()
    val mqttState by connections.mqttState.collectAsState()
    val bridgeHealthy by connections.bridgeHealthy.collectAsState()
    val connected = mqttState is MqttConnectionState.Connected
    val startupProgress: StartupProgress = koinInject()
    val fetchStates by startupProgress.states.collectAsState()

    var dismissed by remember { mutableStateOf(false) }
    var pastGrace by remember { mutableStateOf(false) }
    var minElapsed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_TOTAL_MS)
        minElapsed = true
    }
    LaunchedEffect(Unit) {
        delay(SPLASH_MANUAL_DISMISS_GRACE_MS)
        pastGrace = true
    }
    // Dismiss only once the home connection is up AND the 2 s minimum has passed.
    LaunchedEffect(connected, minElapsed) {
        if (connected && minElapsed) dismissed = true
    }

    AnimatedVisibility(visible = !dismissed, exit = fadeOut()) {
        val homeTask = MkSplashTask(
            label = when (mqttState) {
                is MqttConnectionState.Connected -> "Yhteys kotiin"
                is MqttConnectionState.Failed -> "Ei yhteyttä kotiverkkoon"
                else -> "Yhdistetään kotiin…"
            },
            state = when (mqttState) {
                is MqttConnectionState.Connected -> SplashTaskState.Ok
                is MqttConnectionState.Failed -> SplashTaskState.Failed
                else -> SplashTaskState.Pending
            },
        )
        val serverTask = MkSplashTask(
            label = when {
                bridgeHealthy == true -> "Palvelin valmis"
                bridgeHealthy == false -> "Palvelin ei vastaa"
                else -> "Yhdistetään palvelimeen…"
            },
            state = when {
                bridgeHealthy == true -> SplashTaskState.Ok
                bridgeHealthy == false -> SplashTaskState.Failed
                else -> SplashTaskState.Pending
            },
        )
        // One checklist item per boot fetch (weather/news/calendar/metrics),
        // reported into StartupProgress by the Koti view-model.
        val fetchTasks = StartupProgress.LABELS.map { (key, label) ->
            MkSplashTask(
                label = label,
                state = when (fetchStates[key]) {
                    StartupState.Ok -> SplashTaskState.Ok
                    StartupState.Failed -> SplashTaskState.Failed
                    else -> SplashTaskState.Pending
                },
            )
        }
        // Failure is a definitive "not on the home network" signal — offer the
        // way out immediately rather than waiting out the full grace period.
        val offerDismiss = !connected && (pastGrace || mqttState is MqttConnectionState.Failed)
        MkSplash(
            tasks = listOf(homeTask, serverTask) + fetchTasks,
            onDismiss = if (offerDismiss) { { dismissed = true } } else null,
        )
    }
}
