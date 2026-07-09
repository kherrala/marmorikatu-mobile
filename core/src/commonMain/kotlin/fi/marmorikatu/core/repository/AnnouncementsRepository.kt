package fi.marmorikatu.core.repository

import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.transport.bridge.BridgeApi
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
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    _streaming.value = true
                    bridge.announcementStream(lastEventId).collect { event ->
                        backoffMs = 1_000L
                        lastEventId = event.id
                        _announcements.emit(event)
                        _recent.value = (listOf(event) + _recent.value).take(recentLimit)
                    }
                } catch (e: Exception) {
                    log.d { "announcement stream dropped: ${e.message}" }
                }
                _streaming.value = false
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _streaming.value = false
    }

    override suspend fun history(limit: Int): List<Announcement> = bridge.announcementHistory(limit)
}
