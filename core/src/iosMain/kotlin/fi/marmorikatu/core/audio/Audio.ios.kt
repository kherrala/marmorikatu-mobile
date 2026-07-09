package fi.marmorikatu.core.audio

import fi.marmorikatu.core.log.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithInt
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.dataWithBytes(if (isEmpty()) null else pinned.addressOf(0), size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
actual class AudioRecorder actual constructor() {
    private val log = logger("recorder")
    private var recorder: AVAudioRecorder? = null
    private var fileUrl: NSURL? = null

    actual suspend fun start() {
        cancel()
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)

        val url = NSURL.fileURLWithPath(
            NSTemporaryDirectory() + "mk-rec-" + NSUUID().UUIDString + ".m4a"
        )
        val settings = mapOf<Any?, Any?>(
            "AVFormatIDKey" to NSNumber.numberWithInt(kAudioFormatMPEG4AAC.toInt()),
            "AVSampleRateKey" to NSNumber.numberWithDouble(16_000.0),
            "AVNumberOfChannelsKey" to NSNumber.numberWithInt(1),
        )
        val rec = AVAudioRecorder(url, settings, null)
        rec.prepareToRecord()
        rec.record()
        recorder = rec
        fileUrl = url
        log.d { "recording to ${url.lastPathComponent}" }
    }

    actual suspend fun stop(): RecordedAudio? {
        val rec = recorder ?: return null
        val url = fileUrl
        recorder = null
        fileUrl = null
        rec.stop()
        val data = url?.let { NSData.dataWithContentsOfURL(it) }
        // The temp file is read into memory; leaving it behind fills the
        // container's tmp directory one recording at a time.
        url?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        val bytes = data?.toByteArray() ?: return null
        return if (bytes.isEmpty()) null else RecordedAudio(bytes, "audio/mp4", "recording.m4a")
    }

    actual fun cancel() {
        recorder?.stop()
        recorder = null
        fileUrl?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
        fileUrl = null
    }
}

/**
 * Sequential WAV playback. `AVAudioPlayer.delegate` is a weak ObjC property,
 * so the delegate is a long-lived field rather than a per-clip local that the
 * Kotlin/Native GC could free mid-playback (the completion callback would
 * then never fire and the queue would stall forever).
 *
 * All mutation happens on the main queue: `enqueue` is called from coroutines
 * and `audioPlayerDidFinishPlaying` from CoreAudio, so the queue would
 * otherwise be touched from two threads.
 */
@OptIn(ExperimentalForeignApi::class)
actual class AudioPlayer actual constructor() {
    private val log = logger("player")
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val queue = ArrayDeque<ByteArray>()
    private var current: AVAudioPlayer? = null

    private val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            playNext()
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            log.w { "decode error: ${error?.localizedDescription}" }
            playNext()
        }
    }

    actual fun enqueue(wavBytes: ByteArray) = onMain {
        queue.addLast(wavBytes)
        if (current == null) playNext()
    }

    private fun playNext() {
        val bytes = queue.removeFirstOrNull()
        if (bytes == null) {
            current = null
            _state.value = PlayerState.Idle
            return
        }
        val player = AVAudioPlayer(bytes.toNSData(), error = null)
        if (player == null) {
            log.w { "failed to create player for ${bytes.size} byte clip" }
            playNext()
            return
        }
        player.delegate = delegate
        current = player
        _state.value = PlayerState.Playing
        player.play()
    }

    actual fun stop() = onMain {
        queue.clear()
        current?.stop()
        current = null
        _state.value = PlayerState.Idle
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block()
        else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}

actual class MicPermission actual constructor() {
    actual suspend fun ensureGranted(): Boolean {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionGranted) return true
        val deferred = CompletableDeferred<Boolean>()
        session.requestRecordPermission { granted -> deferred.complete(granted) }
        return deferred.await()
    }
}
