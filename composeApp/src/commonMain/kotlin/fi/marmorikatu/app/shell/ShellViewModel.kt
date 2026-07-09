package fi.marmorikatu.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.VoiceState
import fi.marmorikatu.core.audio.AudioPlayer
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.background.BackgroundMode
import fi.marmorikatu.core.haptics.Haptics
import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.lifecycle.ConnectionManager
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.AssistantRepository
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import fi.marmorikatu.core.speech.SttEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the app chrome: which surface and tab are showing, the theme, and the
 * voice dock's state machine.
 *
 * In the design prototype the voice states were driven by timers; here they
 * are driven by the real pipeline — microphone → Whisper → the assistant →
 * Piper audio — so the dock reflects what the house is actually doing.
 */
class ShellViewModel(
    private val configStore: ConfigStore,
    private val connections: ConnectionManager,
    private val announcementsRepo: AnnouncementsRepository,
    private val assistantRepo: AssistantRepository,
    private val micPermission: MicPermission,
    private val haptics: Haptics,
    private val backgroundMode: BackgroundMode,
    private val audioPlayer: AudioPlayer,
    private val serverStt: SpeechToText,
    private val serverTts: SpeechOutput,
    private val platformStt: SpeechToText,
    private val platformTts: SpeechOutput,
) : ViewModel() {

    // Kid mode and the theme survive a restart: a child's phone should stay a
    // child's phone, and the theme is a preference, not a session detail.
    private val _surface = MutableStateFlow(
        if (configStore.config.value.kidMode) Surface.Kid else Surface.Phone
    )
    val surface: StateFlow<Surface> = _surface.asStateFlow()

    private val _tab = MutableStateFlow(Tab.Koti)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    // Light is the default: these phones are used in daylight far more
    // often than the dim hallway the kiosk lives in.
    private val _dark = MutableStateFlow(configStore.config.value.darkTheme)
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    private val _voice = MutableStateFlow(VoiceState.Idle)
    val voice: StateFlow<VoiceState> = _voice.asStateFlow()

    /** Live partial transcript while listening, or the spoken sentence. */
    private val _voiceLine = MutableStateFlow<String?>(null)
    val voiceLine: StateFlow<String?> = _voiceLine.asStateFlow()

    private val _voiceHint = MutableStateFlow<String?>(null)
    val voiceHint: StateFlow<String?> = _voiceHint.asStateFlow()

    /** Unread announcements drive the bell badge. */
    val recentAnnouncements: StateFlow<List<Announcement>> = announcementsRepo.recent

    private val _unreadCount = MutableStateFlow(0)
    /** Drives the bell badge on the header and the Tapahtumat tab. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var listenJob: Job? = null
    private var activeStt: SpeechToText? = null
    private val history = mutableListOf<ChatMessage>()

    /**
     * Prefer the phone's own speech engines: they answer instantly and work
     * without the house server. Both fall back to the server pipeline when the
     * platform reports the engine unavailable (notably Finnish STT on iOS).
     */
    val useNativeStt: Boolean get() = configStore.config.value.useNativeStt
    val useNativeTts: Boolean get() = configStore.config.value.useNativeTts

    fun setUseNativeStt(value: Boolean) = configStore.update { it.copy(useNativeStt = value) }
    fun setUseNativeTts(value: Boolean) = configStore.update { it.copy(useNativeTts = value) }

    fun start() = connections.start()

    fun setSurface(surface: Surface) {
        _surface.value = surface
        configStore.update { it.copy(kidMode = surface == Surface.Kid) }
    }

    /** Leaves kid mode; the shell then picks phone or tablet by window width. */
    fun exitKidMode() = setSurface(Surface.Phone)

    fun setTab(tab: Tab) {
        _tab.value = tab
        if (tab == Tab.Tapahtumat) markAnnouncementsRead()
    }

    fun toggleTheme() {
        val dark = !_dark.value
        _dark.value = dark
        configStore.update { it.copy(darkTheme = dark) }
    }

    fun markAnnouncementsRead() {
        _unreadCount.value = 0
    }

    val backgroundSupported: Boolean get() = backgroundMode.supported
    val backgroundEnabled: Boolean get() = configStore.config.value.backgroundEnabled
    val hapticsEnabled: Boolean get() = configStore.config.value.hapticsEnabled

    fun setHapticsEnabled(value: Boolean) = configStore.update { it.copy(hapticsEnabled = value) }

    /** Asks for the notification permission before starting the service. */
    fun setBackgroundEnabled(value: Boolean) {
        viewModelScope.launch {
            val allowed = !value || backgroundMode.ensurePermission()
            if (value && !allowed) return@launch
            configStore.update { it.copy(backgroundEnabled = value) }
            backgroundMode.setEnabled(value)
        }
    }

    init {
        // Restore the background service across launches.
        if (configStore.config.value.backgroundEnabled && backgroundMode.supported) {
            backgroundMode.setEnabled(true)
        }
        viewModelScope.launch {
            announcementsRepo.announcements.collect { event ->
                if (_tab.value != Tab.Tapahtumat) _unreadCount.value += 1
                // Priority 0 is a real alarm; it buzzes whatever the setting says.
                when {
                    event.priority == 0 -> haptics.alert()
                    event.priority == 1 && hapticsEnabled -> haptics.warn()
                }
            }
        }
    }

    // --- Voice ---------------------------------------------------------------

    /** Tap the dock's mic: one press-to-talk turn. */
    fun onMic() {
        if (_voice.value != VoiceState.Idle) {
            stopListening()
            return
        }
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            if (!micPermission.ensureGranted()) {
                _voiceHint.value = "mikrofonilupa puuttuu"
                return@launch
            }
            val stt = pickStt().also { activeStt = it }
            _voice.value = VoiceState.Listening
            _voiceLine.value = null
            _voiceHint.value = null

            // Never sit in Listening forever: an engine can go quiet without
            // ever emitting a Final or an Error.
            val watchdog = launch {
                delay(LISTEN_TIMEOUT_MS)
                if (_voice.value == VoiceState.Listening) {
                    runCatching { stt.stopListening() }
                    _voiceHint.value = "en kuullut mitään"
                    _voice.value = VoiceState.Idle
                }
            }

            try {
                stt.listen().collect { event ->
                    when (event) {
                        is SttEvent.Partial -> _voiceHint.value = event.text
                        is SttEvent.Final -> {
                            watchdog.cancel()
                            converse(event.text)
                        }
                        is SttEvent.Error -> {
                            watchdog.cancel()
                            onSttFailure(stt, event.message)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _voiceHint.value = e.message
            } finally {
                watchdog.cancel()
                activeStt = null
                if (_voice.value == VoiceState.Listening) _voice.value = VoiceState.Idle
            }
        }
    }

    /**
     * A native engine that cannot hear — no Finnish model, no recogniser on the
     * device — is worth one retry through the server pipeline before giving up.
     */
    private suspend fun onSttFailure(failed: SpeechToText, message: String) {
        if (failed !== serverStt) {
            _voiceHint.value = "kokeillaan palvelinta…"
            activeStt = serverStt
            val recovered = runCatching {
                var text: String? = null
                serverStt.listen().collect { event ->
                    if (event is SttEvent.Final) text = event.text
                }
                text
            }.getOrNull()
            if (!recovered.isNullOrBlank()) {
                converse(recovered)
                return
            }
        }
        _voiceHint.value = message
        _voice.value = VoiceState.Idle
    }

    /** Release / cancel: the server engine records until told to stop. */
    fun stopListening() {
        val stt = activeStt ?: if (useNativeStt) platformStt else serverStt
        viewModelScope.launch { runCatching { stt.stopListening() } }
    }

    private suspend fun pickStt(): SpeechToText =
        if (useNativeStt && platformStt.isAvailable()) platformStt else serverStt

    private suspend fun converse(userText: String) {
        _voice.value = VoiceState.Thinking
        _voiceLine.value = userText
        _voiceHint.value = null
        history += ChatMessage.user(userText)

        val nativeTts = useNativeTts && platformTts.isAvailable()
        var lastSentence: String? = null

        try {
            assistantRepo.chat(history.toList()).collect { event ->
                when (event) {
                    is ChatEvent.ToolUse -> _voiceHint.value = event.tool
                    is ChatEvent.Text -> speak(event.text, nativeTts, lastSentence)
                        .also { lastSentence = event.text }
                    is ChatEvent.Audio -> {
                        if (!nativeTts) audioPlayer.enqueue(event.wav)
                        event.text?.let {
                            if (it != lastSentence) {
                                _voice.value = VoiceState.Speaking
                                _voiceLine.value = it
                                lastSentence = it
                            }
                        }
                    }
                    is ChatEvent.Screenshot -> Unit
                    is ChatEvent.Done -> {
                        history += ChatMessage.assistant(event.response)
                        if (lastSentence == null && event.response.isNotBlank()) {
                            _voice.value = VoiceState.Speaking
                            _voiceLine.value = event.response
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _voiceHint.value = e.message
        }
        _voice.value = VoiceState.Idle
        _voiceHint.value = null
    }

    private suspend fun speak(text: String, nativeTts: Boolean, last: String?) {
        if (text == last) return
        _voice.value = VoiceState.Speaking
        _voiceLine.value = text
        if (nativeTts) platformTts.speak(text)
    }

    private companion object {
        /** How long the dock stays in Listening before giving up. */
        const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
