package fi.marmorikatu.app.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.VoiceState
import fi.marmorikatu.core.audio.MicPermission
import fi.marmorikatu.core.background.BackgroundMode
import fi.marmorikatu.core.haptics.Haptics
import fi.marmorikatu.core.config.AssistantGender
import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.config.SpeechLanguage
import fi.marmorikatu.core.lifecycle.ConnectionManager
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.AssistantRepository
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.speech.SpeechToText
import fi.marmorikatu.core.speech.SttEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One item in the conversation stream — a user request or a single response
 * sentence. [seq] is a stable id for list-placement animations.
 */
data class VoiceStreamItem(val seq: Int, val text: String, val isUser: Boolean)

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

    private val _voiceHint = MutableStateFlow<String?>(null)
    val voiceHint: StateFlow<String?> = _voiceHint.asStateFlow()

    /**
     * The conversation stream: user requests and each spoken response sentence
     * as separate items, newest FIRST (index 0). The overlay renders the newest
     * big at the top and slides older ones down, fading. Persists across the
     * session so re-opening the overlay shows the backlog.
     */
    private val _stream = MutableStateFlow<List<VoiceStreamItem>>(emptyList())
    val stream: StateFlow<List<VoiceStreamItem>> = _stream.asStateFlow()

    private var streamSeq = 0
    private fun pushStream(text: String, isUser: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        _stream.update { (listOf(VoiceStreamItem(streamSeq++, t, isUser)) + it).take(MAX_STREAM) }
    }

    /** Unread announcements drive the bell badge. */
    val recentAnnouncements: StateFlow<List<Announcement>> = announcementsRepo.recent

    private val _unreadCount = MutableStateFlow(0)
    /** Drives the bell badge on the header and the Tapahtumat tab. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var listenJob: Job? = null
    private var activeStt: SpeechToText? = null
    private val history = mutableListOf<ChatMessage>()

    /**
     * The assistant persona: drives the avatar face and the spoken voice. A
     * StateFlow so the live overlay's avatar switches the moment it changes in
     * settings.
     */
    private val _assistantGender = MutableStateFlow(configStore.config.value.assistantGender)
    val assistantGender: StateFlow<AssistantGender> = _assistantGender.asStateFlow()

    fun setAssistantGender(value: AssistantGender) {
        _assistantGender.value = value
        configStore.update { it.copy(assistantGender = value) }
        platformTts.useVoice(value)
    }

    /**
     * The voice language. Finnish is the default, but its native STT/TTS assets
     * are absent on some devices (many iPads), where the assistant silently does
     * nothing — switching to English gives a working voice on any device.
     */
    private val _speechLanguage = MutableStateFlow(configStore.config.value.speechLanguage)
    val speechLanguage: StateFlow<SpeechLanguage> = _speechLanguage.asStateFlow()

    fun setSpeechLanguage(value: SpeechLanguage) {
        _speechLanguage.value = value
        configStore.update { it.copy(speechLanguage = value) }
        platformStt.useLanguage(value)
        // Reapply the language and then the persona, so the voice matches both.
        platformTts.useLanguage(value)
        platformTts.useVoice(_assistantGender.value)
    }

    fun start() = connections.start()

    fun setSurface(surface: Surface) {
        _surface.value = surface
        configStore.update { it.copy(kidMode = surface == Surface.Kid) }
        // The kiosk (tablet surface) keeps its connections live in the background
        // so light state is always current; phones disconnect to save battery.
        connections.keepAlive.value = surface == Surface.Tablet
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
        // Apply the persisted language + persona to the native engines at
        // startup (language first, so the persona picks within the right locale).
        platformStt.useLanguage(_speechLanguage.value)
        platformTts.useLanguage(_speechLanguage.value)
        platformTts.useVoice(_assistantGender.value)
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

    /**
     * Tap the mic. From Idle/Ready (VALMIS) this starts one turn — listen →
     * answer → back to Ready with the overlay still open. While the assistant is
     * actively listening/thinking/speaking, a tap stops and closes it (LOPETA).
     */
    fun onMic() {
        when (_voice.value) {
            VoiceState.Idle, VoiceState.Ready -> startTurn(null)
            else -> stopListening()
        }
    }

    /** Fire a canned prompt from the quick-command grid as one turn. */
    fun runQuickCommand(prompt: String) = startTurn(prompt)

    /**
     * One turn: (optionally listen →) answer, then settle in [VoiceState.Ready]
     * with the overlay still open — no auto-relisten, no auto-close. The user
     * continues by tapping the mic or a quick command, or dismisses with X/LOPETA.
     * A farewell-only utterance ("kiitos") signs off and closes.
     */
    private fun startTurn(initial: String?) {
        listenJob?.cancel()
        listenJob = viewModelScope.launch {
            try {
                val text = if (initial != null) initial else {
                    if (!micPermission.ensureGranted()) {
                        _voiceHint.value = "mikrofonilupa puuttuu"
                        return@launch
                    }
                    listenOnce()
                }
                if (text.isNullOrBlank()) return@launch
                if (isFarewell(text)) {
                    speak(GOODBYES.random())
                    _voice.value = VoiceState.Idle   // farewell closes
                    return@launch
                }
                respond(text)
            } finally {
                activeStt = null
                // Settle into Ready (overlay stays) unless we were cancelled by a
                // new turn / LOPETA (which owns the state), or a farewell closed us.
                if (coroutineContext[Job]?.isActive == true && _voice.value != VoiceState.Idle) {
                    _voice.value = VoiceState.Ready
                    _voiceHint.value = null
                }
            }
        }
    }

    /**
     * One listening turn. Returns the recognised text, or null when the user
     * stayed silent / the engine failed. The native engine finalises on its own
     * silence detection; the timeout is a backstop for an engine that never
     * closes its flow.
     */
    private suspend fun listenOnce(): String? {
        // Gate on availability first: on iOS this triggers the one-time
        // SFSpeechRecognizer authorization prompt (previously done via pickStt's
        // isAvailable check, which the conversation refactor dropped — without it
        // recognition silently does nothing).
        if (!platformStt.isAvailable()) {
            _voiceHint.value = "puheentunnistus ei ole käytettävissä"
            return null
        }
        val stt = platformStt.also { activeStt = it }
        _voice.value = VoiceState.Listening
        _voiceHint.value = null
        var text: String? = null
        withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            stt.listen().collect { event ->
                when (event) {
                    is SttEvent.Partial -> _voiceHint.value = event.text
                    is SttEvent.Final -> text = event.text
                    is SttEvent.Error -> _voiceHint.value = event.message
                }
            }
        } ?: runCatching { stt.stopListening() }
        activeStt = null
        return text
    }

    /**
     * Understand [userText] and speak the answer: Thinking while the assistant
     * works, Speaking as each sentence is synthesised on-device. Leaves the
     * voice state alone on completion — the [conversation] loop reopens the mic.
     */
    private suspend fun respond(userText: String) {
        pushStream(userText, isUser = true)   // the request joins the stream top
        _voice.value = VoiceState.Thinking
        _voiceHint.value = null
        history += ChatMessage.user(userText)

        val nativeTts = platformTts.isAvailable()
        var lastSentence: String? = null
        var spokeAny = false

        // Each response sentence is pushed to the top of the stream as its own
        // item (the previous ones slide down and fade) and spoken in turn —
        // matching how the assistant streams sentences from its tools.
        suspend fun sentence(text: String) {
            val t = text.trim()
            if (t.isEmpty() || t == lastSentence) return
            lastSentence = t
            spokeAny = true
            _voice.value = VoiceState.Speaking
            pushStream(t, isUser = false)
            // Native speak() suspends until the utterance finishes (paces the
            // stream and reopens the mic only after we stop talking); without it,
            // reveal fragments ~1.3 s apart so the stream still grows readably.
            if (nativeTts) platformTts.speak(t) else delay(1300)
        }

        try {
            assistantRepo.chat(history.toList()).collect { event ->
                when (event) {
                    is ChatEvent.ToolUse -> _voiceHint.value = mcpToolLabel(event.tool)
                    is ChatEvent.Text -> sentence(event.text)
                    // Native TTS speaks the text; the server-synthesised WAV that
                    // rides this event is ignored (voice is device-only now).
                    is ChatEvent.Audio -> event.text?.let { sentence(it) }
                    is ChatEvent.Screenshot -> Unit
                    is ChatEvent.Done -> {
                        history += ChatMessage.assistant(event.response)
                        if (!spokeAny && event.response.isNotBlank()) sentence(event.response)
                    }
                }
            }
        } catch (e: Exception) {
            _voiceHint.value = e.message
        }
    }

    /** Speak a short line (a goodbye) on-device, pushing it onto the stream. */
    private suspend fun speak(line: String) {
        _voice.value = VoiceState.Speaking
        _voiceHint.value = null
        pushStream(line, isUser = false)
        if (platformTts.isAvailable()) platformTts.speak(line)
    }

    /**
     * A farewell-only utterance ends the conversation (from the kiosk's
     * [FAREWELL_PATTERNS]); only matches when the *whole* transcript is one, so
     * "kiitos paljon avusta" keeps talking but "kiitos" signs off.
     */
    private fun isFarewell(text: String): Boolean = FAREWELL_PATTERNS.matches(text.trim())

    /**
     * "LOPETA": stop the whole conversation now — cancel the loop (which unwinds
     * any in-flight STT or assistant stream), silence the voice, drop to idle.
     */
    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        val stt = activeStt
        activeStt = null
        viewModelScope.launch { runCatching { stt?.stopListening() } }
        platformTts.stop()
        _voice.value = VoiceState.Idle
        _voiceHint.value = null
    }

    private companion object {
        /** Backstop for a listening turn the native engine never ends. */
        const val LISTEN_TIMEOUT_MS = 15_000L

        /** Cap on the conversation stream (requests + response sentences). */
        const val MAX_STREAM = 40

        /** Farewell-only utterances that end the conversation (kiosk parity). */
        val FAREWELL_PATTERNS = Regex(
            "^(heippa|heihei|hei\\s*hei|näkemiin|nähdään|moi\\s*moi|moikka|kiitos|kiitti|" +
                "lopeta|riittää|selvä|bye|goodbye|see\\s*you)[.!?]?\\s*$",
            RegexOption.IGNORE_CASE,
        )

        val GOODBYES = listOf("Heippa!", "Nähdään!", "Moikka!", "Hei hei!")
    }
}
