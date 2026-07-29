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
            // Anchor on the newest known id so the stream only delivers what
            // happened after this (re)connect. Without a Last-Event-ID the bridge
            // replays its whole ring buffer (~200 events); resuming from a STALE id
            // after a long background gap replays the entire gap — the reason a
            // reopened phone spoke back hours of old notifications. [stop] clears
            // the anchor, so every foreground re-anchors to "newest" here; a
            // mid-stream network blip keeps its id and resumes (see the retry loop).
            if (lastEventId == null) {
                runCatching { bridge.announcementHistory(limit = 1) }
                    .getOrNull()
                    ?.maxByOrNull { it.id }
                    ?.let { newest ->
                        lastEventId = newest.id
                        // Seed the feed on a cold start; keep it on a warm restart.
                        if (_recent.value.isEmpty()) _recent.value = listOf(newest)
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
        // Drop the resume anchor: the next start() re-anchors to the newest event
        // instead of replaying everything that happened while backgrounded. A
        // mid-stream reconnect (the internal retry loop) never calls stop(), so it
        // still resumes from its id and doesn't miss events during a brief blip.
        lastEventId = null
    }

    override suspend fun history(limit: Int): List<Announcement> = bridge.announcementHistory(limit)

    override suspend fun cameraSnapshot(): Announcement? = bridge.cameraSnapshot()
}
