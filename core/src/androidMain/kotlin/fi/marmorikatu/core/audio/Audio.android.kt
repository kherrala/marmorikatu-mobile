package fi.marmorikatu.core.audio

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.platform.AndroidContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual class AudioRecorder actual constructor() {
    private val log = logger("recorder")
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    actual suspend fun start() = withContext(Dispatchers.IO) {
        cancel()
        val file = File.createTempFile("mk-rec", ".m4a", AndroidContext.app.cacheDir)
        val mr = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(AndroidContext.app)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioSamplingRate(16_000)
        mr.setAudioChannels(1)
        mr.setAudioEncodingBitRate(32_000)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
        mr.start()
        recorder = mr
        outputFile = file
        log.d { "recording to ${file.name}" }
    }

    actual suspend fun stop(): RecordedAudio? = withContext(Dispatchers.IO) {
        val mr = recorder ?: return@withContext null
        val file = outputFile
        recorder = null
        outputFile = null
        val stopped = runCatching {
            mr.stop()
            mr.release()
        }.isSuccess
        if (!stopped || file == null || !file.exists() || file.length() == 0L) {
            runCatching { mr.release() }
            file?.delete()
            return@withContext null
        }
        val bytes = file.readBytes()
        file.delete()
        RecordedAudio(bytes, "audio/mp4", "recording.m4a")
    }

    actual fun cancel() {
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}

/**
 * Sequential WAV playback. Every mutation of [queue]/[current] and every
 * MediaPlayer call happens on the main thread: `enqueue` is called from
 * coroutines while completion callbacks arrive on MediaPlayer's own thread.
 * Temp-file writes are pushed to an IO dispatcher rather than blocking it.
 */
actual class AudioPlayer actual constructor() {
    private val log = logger("player")
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    actual val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<ByteArray>()
    private var current: MediaPlayer? = null

    actual fun enqueue(wavBytes: ByteArray) {
        scope.launch {
            queue.addLast(wavBytes)
            if (current == null) playNext()
        }
    }

    private suspend fun playNext() {
        val bytes = queue.removeFirstOrNull()
        if (bytes == null) {
            current = null
            _state.value = PlayerState.Idle
            return
        }
        val file = try {
            withContext(Dispatchers.IO) {
                File.createTempFile("mk-tts", ".wav", AndroidContext.app.cacheDir)
                    .apply { writeBytes(bytes) }
            }
        } catch (e: Exception) {
            log.w(e) { "failed to stage clip" }
            playNext()
            return
        }

        try {
            val mp = MediaPlayer()
            current = mp
            _state.value = PlayerState.Playing
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { finish(mp, file) }
            mp.setOnErrorListener { _, what, extra ->
                log.w { "playback error $what/$extra" }
                finish(mp, file)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            log.w(e) { "failed to play clip" }
            file.delete()
            playNext()
        }
    }

    private fun finish(mp: MediaPlayer, file: File) {
        scope.launch {
            runCatching { mp.release() }
            if (current === mp) current = null
            withContext(Dispatchers.IO) { file.delete() }
            playNext()
        }
    }

    actual fun stop() {
        scope.launch {
            queue.clear()
            current?.let { runCatching { it.stop() }; runCatching { it.release() } }
            current = null
            _state.value = PlayerState.Idle
        }
    }
}
