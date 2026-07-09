package fi.marmorikatu.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.transport.mcp.McpState
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import org.koin.compose.viewmodel.koinViewModel

/**
 * Temporary developer screen proving the plumbing end to end. Every section
 * maps to an acceptance-checklist item; the designed UI replaces this.
 */
@Composable
fun DebugScreen(viewModel: DebugViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.start() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionSection(viewModel) }
            item { TemperatureSection(viewModel) }
            item { LightsSection(viewModel) }
            item { TvSection(viewModel) }
            item { VoiceSection(viewModel) }
            item { AnnouncementsSection(viewModel) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean?) {
    val color = when (ok) {
        true -> Color(0xFF2E7D32)
        false -> Color(0xFFC62828)
        null -> Color(0xFF9E9E9E)
    }
    Spacer(
        modifier = Modifier.size(12.dp).clip(CircleShape).background(color)
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean?, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(ok)
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ConnectionSection(viewModel: DebugViewModel) {
    val mqtt by viewModel.mqttState.collectAsState()
    val mcp by viewModel.mcpState.collectAsState()
    val bridge by viewModel.bridgeHealthy.collectAsState()
    val plc by viewModel.plcStatus.collectAsState()
    val host by viewModel.serverHost.collectAsState()

    SectionCard("Yhteydet") {
        StatusRow(
            "MQTT", mqtt is MqttConnectionState.Connected,
            when (val s = mqtt) {
                is MqttConnectionState.Failed -> s.message
                else -> s::class.simpleName ?: ""
            },
        )
        StatusRow(
            "MCP", mcp is McpState.Connected,
            (mcp as? McpState.Connected)?.lastLatencyMs?.let { "${it} ms" } ?: mcp::class.simpleName.orEmpty(),
        )
        StatusRow("Claude Bridge", bridge)
        StatusRow(
            "PLC", if (plc.publishCount > 0) plc.modbusConnected else null,
            "julkaisuja ${plc.publishCount}, komentoja ${plc.commandsApplied}/${plc.commandsReceived}",
        )
        var hostField by remember(host) { mutableStateOf(host) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hostField,
                onValueChange = { hostField = it },
                label = { Text("Palvelimen osoite") },
                modifier = Modifier.fillMaxWidth(0.7f),
                singleLine = true,
            )
            OutlinedButton(onClick = { viewModel.updateServerHost(hostField) }) {
                Text("Aseta")
            }
        }
    }
}

@Composable
private fun TemperatureSection(viewModel: DebugViewModel) {
    val temps by viewModel.roomTemperatures.collectAsState()
    SectionCard("Lämpötilat") {
        if (temps.isEmpty()) {
            Text("Odotetaan MQTT-dataa…", style = MaterialTheme.typography.bodySmall)
        }
        temps.forEach { room ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(room.name)
                Text("${room.celsius} °C")
            }
        }
    }
}

@Composable
private fun LightsSection(viewModel: DebugViewModel) {
    val lights by viewModel.lights.collectAsState()
    SectionCard("Valot") {
        OutlinedButton(onClick = viewModel::allLightsOff) { Text("Kaikki pois") }
        Floor.entries.forEach { floor ->
            val floorLights = lights.filter { it.floor == floor }
            if (floorLights.isEmpty()) return@forEach
            Text(floor.label, style = MaterialTheme.typography.labelLarge)
            floorLights.forEach { light ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(light.name + if (light.pendingOn != null) " …" else "")
                    Switch(
                        checked = light.displayedOn,
                        onCheckedChange = { viewModel.toggleLight(light) },
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TvSection(viewModel: DebugViewModel) {
    val activities by viewModel.tvActivities.collectAsState()
    val error by viewModel.tvError.collectAsState()
    SectionCard("TV (Harmony)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::refreshTv) { Text("Hae toiminnot") }
            OutlinedButton(onClick = viewModel::tvPowerOff) { Text("Sammuta") }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        activities.forEach { activity ->
            Button(onClick = { viewModel.startTvActivity(activity) }) { Text(activity) }
        }
    }
}

@Composable
private fun VoiceSection(viewModel: DebugViewModel) {
    val log by viewModel.voiceLog.collectAsState()
    val nativeStt by viewModel.useNativeStt.collectAsState()
    val nativeTts by viewModel.useNativeTts.collectAsState()

    SectionCard("Ääniavustaja") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Natiivi STT", style = MaterialTheme.typography.bodySmall)
            Switch(checked = nativeStt, onCheckedChange = viewModel::setUseNativeStt)
            Text("Natiivi TTS", style = MaterialTheme.typography.bodySmall)
            Switch(checked = nativeTts, onCheckedChange = viewModel::setUseNativeTts)
        }
        // A Button's own clickable consumes the press, so the hold-to-talk
        // surface handles pointer input directly.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            viewModel.startListening()
                            tryAwaitRelease()
                            viewModel.stopListening()
                        }
                    )
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Pidä pohjassa ja puhu", color = MaterialTheme.colorScheme.onPrimary)
        }

        // Emulators have no usable microphone; typing a turn exercises the
        // same chat + TTS path the voice flow uses after transcription.
        var command by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Kirjoita komento") },
                modifier = Modifier.fillMaxWidth(0.7f),
                singleLine = true,
            )
            OutlinedButton(onClick = {
                viewModel.sendTextCommand(command)
                command = ""
            }) { Text("Lähetä") }
        }

        log.takeLast(10).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnnouncementsSection(viewModel: DebugViewModel) {
    val recent by viewModel.recentAnnouncements.collectAsState()
    SectionCard("Kuulutukset") {
        if (recent.isEmpty()) {
            Text("Ei kuulutuksia vielä", style = MaterialTheme.typography.bodySmall)
        }
        recent.take(5).forEach { a ->
            Text("• ${a.text}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
