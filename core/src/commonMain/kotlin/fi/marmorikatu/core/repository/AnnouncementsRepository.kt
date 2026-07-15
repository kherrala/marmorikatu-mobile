package fi.marmorikatu.core.repository

import fi.marmorikatu.core.lifecycle.CONNECTED_HOLD
import fi.marmorikatu.core.lifecycle.reconnectDelay
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.transport.bridge.BridgeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

interface AnnouncementsRepository {
    /** Live pushed events while the stream is open. */
    val announcements: SharedFlow<Announcement>

    /** Most recent events (live-updated ring, newest first). */
    val recent: StateFlow<List<Announcement>>

    val streaming: StateFlow<Boolean>

    /** Opens the SSE stream; resumes from the last seen id on reconnect. */
    fun start()
    fun stop()

    suspend fun history(limit: Int = 20): List<Announcement>

    /**
     * The bridge's retained last camera snapshot (with its `image`), or null.
     * Separate from [recent]/[history] — those strip images — so a kiosk can
     * show the last front-yard frame on cold start / reconnect / an idle yard.
     */
    suspend fun cameraSnapshot(): Announcement?
}

class DefaultAnnouncementsRepository(
    private val bridge: BridgeApi,
    private val scope: CoroutineScope,
    private val recentLimit: Int = 20,
) : AnnouncementsRepository {

    private val log = logger("announcements")

    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 16)
    override val announcements: SharedFlow<Announcement> = _announcements.asSharedFlow()

    private val _recent = MutableStateFlow<List<Announcement>>(emptyList())
    override val recent: StateFlow<List<Announcement>> = _recent.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    override val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private var job: Job? = null
    private var lastEventId: Long? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Without a Last-Event-ID the bridge replays its whole ring buffer
            // (~200 events). Anchor on the newest known id so the stream only
            // delivers what happened after the app opened — otherwise every
            // historical event looks like breaking news.
            if (lastEventId == null) {
                runCatching { bridge.announcementHistory(limit = 1) }
                    .getOrNull()
                    ?.maxByOrNull { it.id }
                    ?.let { newest ->
                        lastEventId = newest.id
                        _recent.value = listOf(newest)
                    }
            }

            var failures = 0
            while (isActive) {
                var delivered = false
                try {
                    _streaming.value = true
                    bridge.announcementStream(lastEventId).collect { event ->
                        delivered = true
                        lastEventId = event.id
                        _announcements.emit(event)
                        _recent.value = (listOf(event) + _recent.value).take(recentLimit)
                    }
                } catch (e: CancellationException) {
                    throw e   // never swallow cancellation — let the loop stop
                } catch (e: Exception) {
                    log.d { "announcement stream dropped: ${e.message}" }
                }
                _streaming.value = false
                // A stream that delivered an event was genuinely healthy → reconnect
                // fast. A healthy idle stream stays open on keepalives, so a drop
                // with nothing delivered is a real connection failure that ramps
                // toward the slow poll sparing the radio in the background.
                failures = if (delivered) 0 else failures + 1
                delay(reconnectDelay(failures))
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _streaming.value = false
    }

    override suspend fun history(limit: Int): List<Announcement> = bridge.announcementHistory(limit)

    override suspend fun cameraSnapshot(): Announcement? = bridge.cameraSnapshot()
}
