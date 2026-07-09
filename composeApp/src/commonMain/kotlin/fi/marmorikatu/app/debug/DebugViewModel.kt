package fi.marmorikatu.app.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.core.audio.AudioPlayer
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.lifecycle.ConnectionManager
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.Light
import fi.marmorikatu.core.model.PlcStatus
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.AssistantRepository
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.repository.LightsRepository
import fi.marmorikatu.core.repository.TvRepository
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import fi.marmorikatu.core.speech.SttEvent
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mcp.McpState
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Temporary developer-facing view model that exercises every wired
 * capability. Discarded when the designed UI lands.
 */
class DebugViewModel(
    private val configStore: ConfigStore,
    private val connections: ConnectionManager,
    private val lightsRepo: LightsRepository,
    private val climateRepo: ClimateRepository,
    private val tvRepo: TvRepository,
    private val announcementsRepo: AnnouncementsRepository,
    private val assistantRepo: AssistantRepository,
    private val micPermission: MicPermission,
    private val audioPlayer: AudioPlayer,
    mcpApi: McpApi,
    private val serverStt: SpeechToText,
    private val serverTts: SpeechOutput,
    private val platformStt: SpeechToText,
    private val platformTts: SpeechOutput,
) : ViewModel() {

    val mqttState: StateFlow<MqttConnectionState> = connections.mqttState
    val mcpState: StateFlow<McpState> = mcpApi.state
    val bridgeHealthy: StateFlow<Boolean?> = connections.bridgeHealthy
    val plcStatus: StateFlow<PlcStatus> = climateRepo.plcStatus
    val lights: StateFlow<List<Light>> = lightsRepo.lights
    val roomTemperatures: StateFlow<List<RoomTemperature>> = climateRepo.roomTemperatures
    val recentAnnouncements: StateFlow<List<Announcement>> = announcementsRepo.recent

    val serverHost: StateFlow<String> get() = _serverHost
    private val _serverHost = MutableStateFlow(configStore.config.value.serverHost)

    private val _tvActivities = MutableStateFlow<List<String>>(emptyList())
    val tvActivities: StateFlow<List<String>> = _tvActivities.asStateFlow()

    private val _tvError = MutableStateFlow<String?>(null)
    val tvError: StateFlow<String?> = _tvError.asStateFlow()

    private val _voiceLog = MutableStateFlow<List<String>>(emptyList())
    val voiceLog: StateFlow<List<String>> = _voiceLog.asStateFlow()

    private val _useNativeStt = MutableStateFlow(false)
    val useNativeStt: StateFlow<Boolean> = _useNativeStt.asStateFlow()

    private val _useNativeTts = MutableStateFlow(false)
    val useNativeTts: StateFlow<Boolean> = _useNativeTts.asStateFlow()

    private var listenJob: Job? = null
    private val history = mutableListOf<ChatMessage>()

    fun start() = connections.start()

    fun toggleLight(light: Light) {
        viewModelScope.launch {
            runCatching { lightsRepo.setLight(light.id, !light.displayedOn) }
                .onFailure { log("Valon ohjaus epäonnistui: ${it.message}") }
        }
    }

    fun allLightsOff() {
        viewModelScope.launch {
            runCatching { lightsRepo.setAll(false) }
                .onFailure { log("Kaikki pois epäonnistui: ${it.message}") }
        }
    }

    fun refreshTv() {
        viewModelScope.launch {
            runCatching { tvRepo.activities() }
                .onSuccess {
                    _tvActivities.value = it
                    _tvError.value = if (it.isEmpty()) "Ei toimintoja määritetty" else null
                }
                .onFailure {
                    log("TV-virhe: ${it.message}")
                    _tvError.value = it.message ?: "tuntematon virhe"
                }
        }
    }

    fun startTvActivity(activity: String) {
        viewModelScope.launch {
            runCatching { tvRepo.startActivity(activity) }
                .onFailure { _tvError.value = it.message }
        }
    }

    fun tvPowerOff() {
        viewModelScope.launch {
            runCatching { tvRepo.powerOff() }.onFailure { _tvError.value = it.message }
        }
    }

    fun setUseNativeStt(value: Boolean) { _useNativeStt.value = value }
    fun setUseNativeTts(value: Boolean) { _useNativeTts.value = value }

    /** Hold-to-talk press. */
    fun startListening() {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            if (!micPermission.ensureGranted()) {
                log("Mikrofonilupa puuttuu")
                return@launch
            }
            // Resolve once: the release must reach the engine that is running,
            // even if the native/server switch is flipped mid-utterance.
            val stt = pickStt().also { activeStt = it }
            log("Kuunnellaan (${stt.name})…")
            try {
                stt.listen().collect { event ->
                    when (event) {
                        is SttEvent.Partial -> log("… ${event.text}")
                        is SttEvent.Final -> {
                            log("Sinä: ${event.text}")
                            converse(event.text)
                        }
                        is SttEvent.Error -> log("STT-virhe: ${event.message}")
                    }
                }
            } catch (e: Exception) {
                log("STT-virhe: ${e.message}")
            } finally {
                activeStt = null
            }
        }
    }

    /**
     * Hold-to-talk release. A very fast tap can release before the press
     * coroutine resolved its engine; the engines remember an early stop, so
     * fall back to the one the switch currently selects.
     */
    fun stopListening() {
        val stt = activeStt ?: if (_useNativeStt.value) platformStt else serverStt
        viewModelScope.launch { runCatching { stt.stopListening() } }
    }

    /** Types a turn instead of speaking it — used to exercise the chat and
     *  TTS legs on emulators, which have no usable microphone. */
    fun sendTextCommand(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            log("Sinä: $text")
            converse(text)
        }
    }

    private var activeStt: SpeechToText? = null

    private suspend fun pickStt(): SpeechToText =
        if (_useNativeStt.value && platformStt.isAvailable()) platformStt else serverStt

    private suspend fun converse(userText: String) {
        history += ChatMessage.user(userText)
        val nativeTts = _useNativeTts.value && platformTts.isAvailable()
        var lastSentence: String? = null
        var spoke = false

        // The bridge carries each sentence inside its `audio` event; only
        // older builds emit standalone `text` events. Handle both without
        // saying the same sentence twice.
        suspend fun sentence(text: String) {
            if (text == lastSentence) return
            lastSentence = text
            spoke = true
            log("Avustaja: $text")
            if (nativeTts) platformTts.speak(text)
        }

        try {
            assistantRepo.chat(history.toList()).collect { event ->
                when (event) {
                    is ChatEvent.ToolUse -> log("[työkalu: ${event.tool}]")
                    is ChatEvent.Text -> sentence(event.text)
                    is ChatEvent.Audio -> {
                        if (!nativeTts) audioPlayer.enqueue(event.wav)
                        event.text?.let { sentence(it) }
                    }
                    is ChatEvent.Screenshot -> log("[kuvakaappaus]")
                    is ChatEvent.Done -> {
                        history += ChatMessage.assistant(event.response)
                        if (!spoke && event.response.isNotBlank()) log("Avustaja: ${event.response}")
                    }
                }
            }
        } catch (e: Exception) {
            log("Keskusteluvirhe: ${e.message}")
        }
    }

    fun log(message: String) {
        _voiceLog.value = (_voiceLog.value + message).takeLast(30)
    }

    fun updateServerHost(host: String) {
        _serverHost.value = host
        configStore.update { it.copy(serverHost = host) }
    }
}
