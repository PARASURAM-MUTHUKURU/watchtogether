package com.watchtogether.app.audio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.watchtogether.app.ui.UIUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VoiceManager(
    private val activity: Activity,
    private val onSendJson: (JSONObject) -> Unit,
    private val onSetPlayerVolume: (Float) -> Unit,
    private val onShowHud: (String) -> Unit,
    private val onHideHudDelayed: () -> Unit,
    private val onNote: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newFixedThreadPool(2)
    private val audioWorker: ExecutorService = Executors.newSingleThreadExecutor()
    private val density = activity.resources.displayMetrics.density

    // DSA: Power-of-Two Lock-Free Circular Ring Buffer for smooth jitter absorption during live audio streaming
    private val audioJitterBuffer = com.watchtogether.app.dsa.CircularAudioBuffer(powerOfTwoCapacity = 32768)
    private val audioPlaybackTempBuf = ByteArray(2048)

    lateinit var micBtn: TextView
    lateinit var openMicBtn: TextView
    lateinit var floatingMicBtn: TextView

    var isVoiceLocked = false
        private set
    private var isRecording = false
    private var isLiveStreaming = false
    private var isLocalSpeaking = false
    private var micTouchStartY = 0f
    private var isTrackingSwipeUp = false
    private var recordStartTime = 0L
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var activeVoicePlayer: MediaPlayer? = null

    private var liveRecordThread: Thread? = null
    private var liveAudioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var liveAudioTrack: AudioTrack? = null
    private var liveAudioEnhancer: LoudnessEnhancer? = null
    private var isDestroyed = false

    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var isCommModeActive = false

    @Suppress("DEPRECATION")
    private fun enableCommunicationMode() {
        try {
            if (!isCommModeActive) {
                originalAudioMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true
                isCommModeActive = true
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun disableCommunicationMode() {
        try {
            if (isCommModeActive && !isLiveStreaming && liveAudioTrack == null) {
                audioManager.mode = originalAudioMode
                audioManager.isSpeakerphoneOn = false
                isCommModeActive = false
            }
        } catch (_: Exception) {}
    }

    private val incomingSilenceRunnable = Runnable {
        if (!isDestroyed) {
            onSetPlayerVolume(1.0f)
            onHideHudDelayed()
            disableCommunicationMode()
        }
    }

    fun initButtons(mic: TextView, openMic: TextView, floatingMic: TextView) {
        micBtn = mic
        openMicBtn = openMic
        floatingMicBtn = floatingMic
    }

    fun resetMicButtonStates() {
        ui.post {
            isVoiceLocked = false
            isTrackingSwipeUp = false
            if (::micBtn.isInitialized) {
                micBtn.text = "🎙️ Talk"
                micBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#059669"), 14f, Color.parseColor("#10B981"), 1))
                micBtn.scaleX = 1f
                micBtn.scaleY = 1f
            }
            if (::openMicBtn.isInitialized) {
                openMicBtn.text = "🔓 Open"
                openMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 14f, Color.parseColor("#334155"), 1))
                openMicBtn.scaleX = 1f
                openMicBtn.scaleY = 1f
            }
            if (::floatingMicBtn.isInitialized) {
                floatingMicBtn.text = "🎙️"
                floatingMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#E60D1322"), 22f, Color.parseColor("#10B981"), 1))
                floatingMicBtn.scaleX = 1f
                floatingMicBtn.scaleY = 1f
            }
        }
    }

    fun updateMicButtonsState(speaking: Boolean) {
        ui.post {
            if (!isVoiceLocked) return@post
            if (speaking) {
                if (::micBtn.isInitialized) {
                    micBtn.text = "🗣️ Speaking…"
                    micBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#059669"), 14f, Color.parseColor("#34D399"), 2))
                }
                if (::openMicBtn.isInitialized) {
                    openMicBtn.text = "🗣️ Live"
                    openMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#059669"), 14f, Color.parseColor("#34D399"), 2))
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🗣️"
                    floatingMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#E6059669"), 22f, Color.parseColor("#34D399"), 2))
                }
            } else {
                if (::micBtn.isInitialized) {
                    micBtn.text = "🟢 Open Mic"
                    micBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#0F766E"), 14f, Color.parseColor("#14B8A6"), 2))
                }
                if (::openMicBtn.isInitialized) {
                    openMicBtn.text = "🟢 Mute"
                    openMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#0F766E"), 14f, Color.parseColor("#14B8A6"), 2))
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🟢"
                    floatingMicBtn.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#E60F766E"), 22f, Color.parseColor("#14B8A6"), 2))
                }
            }
        }
    }

    fun handleMicTouchEvent(view: View, event: MotionEvent): Boolean {
        view.parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isVoiceLocked) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    isVoiceLocked = false
                    stopLiveVoiceStreaming()
                    resetMicButtonStates()
                    onNote("🎙️ Mic muted")
                    return true
                }

                micTouchStartY = event.rawY
                isTrackingSwipeUp = true
                view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                if (::micBtn.isInitialized) {
                    micBtn.text = "🔴 Talking…"
                    micBtn.background = UIUtils.roundedBackground(density, Color.parseColor("#DC2626"), 14f)
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🗣️"
                    floatingMicBtn.background = UIUtils.roundedBackground(density, Color.parseColor("#DC2626"), 18f)
                }
                startLiveVoiceStreaming()
                onShowHud("🎙️ Hold to talk · Swipe up to lock")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTrackingSwipeUp && !isVoiceLocked) {
                    val deltaY = micTouchStartY - event.rawY
                    val lockThreshold = 18f * density
                    if (deltaY >= lockThreshold) {
                        isVoiceLocked = true
                        isTrackingSwipeUp = false
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                @Suppress("DEPRECATION")
                                (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
                                    android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                                )
                            }
                        } catch (_: Exception) {}

                        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
                            view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()
                        }.start()

                        updateMicButtonsState(speaking = true)
                        onNote("🟢 Open Mic active! Speak freely. Tap to mute.")
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isVoiceLocked) return true
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetMicButtonStates()
                stopLiveVoiceStreaming()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isVoiceLocked) return true
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetMicButtonStates()
                stopLiveVoiceStreaming()
                return true
            }
        }
        return false
    }

    fun toggleOpenMic() {
        if (isVoiceLocked) {
            isVoiceLocked = false
            stopLiveVoiceStreaming()
            resetMicButtonStates()
            onNote("🎙️ Mic muted")
        } else {
            isVoiceLocked = true
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
                        android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            } catch (_: Exception) {}
            if (isRecording) {
                isRecording = false
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (_: Exception) {}
                recorder = null
                recordFile?.delete()
                recordFile = null
            }
            updateMicButtonsState(speaking = false)
            startLiveVoiceStreaming()
            onNote("🟢 Open Mic active! Speak freely. Tap to mute.")
        }
    }

    fun startLiveVoiceStreaming() {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (isLiveStreaming) return
        isLiveStreaming = true
        enableCommunicationMode()

        liveRecordThread = Thread({
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val chunkSize = 1600
                val bufferSize = maxOf(minBufSize, chunkSize * 4)

                val record = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } catch (_: Exception) { null } ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    ui.post { onNote("Cannot start microphone") }
                    isLiveStreaming = false
                    return@Thread
                }

                val sessionId = record.audioSessionId
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                        }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                        }
                    }
                } catch (_: Exception) {}

                record.startRecording()
                liveAudioRecord = record

                val shortBuffer = ShortArray(chunkSize)
                val byteBuffer = ByteArray(chunkSize * 2)
                var lastSpeechTime = 0L
                var localSpeaking = false

                ui.post { onSetPlayerVolume(0.15f) }

                while (isLiveStreaming && !isDestroyed) {
                    val readShorts = record.read(shortBuffer, 0, chunkSize)
                    if (readShorts <= 0) continue

                    var sum = 0.0
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i].toLong()
                        sum += (s * s)
                    }
                    val rms = kotlin.math.sqrt(sum / readShorts)
                    val now = System.currentTimeMillis()
                    val speechThreshold = if (isVoiceLocked) 280.0 else 180.0
                    val isSpeech = rms >= speechThreshold

                    if (isSpeech) {
                        lastSpeechTime = now
                        if (!localSpeaking) {
                            localSpeaking = true
                            isLocalSpeaking = true
                            ui.post {
                                if (isDestroyed) return@post
                                onSetPlayerVolume(0.15f)
                                onShowHud("🗣️ You're speaking…")
                                updateMicButtonsState(speaking = true)
                            }
                        }
                    }

                    if (localSpeaking) {
                        if (isVoiceLocked && !isSpeech && (now - lastSpeechTime > 1200L)) {
                            localSpeaking = false
                            isLocalSpeaking = false
                            ui.post {
                                if (isDestroyed) return@post
                                onSetPlayerVolume(1.0f)
                                onHideHudDelayed()
                                updateMicButtonsState(speaking = false)
                            }
                            worker.execute {
                                onSendJson(JSONObject().put("type", "voice_chunk").put("audio", "").put("speaking", false))
                            }
                        } else {
                            for (i in 0 until readShorts) {
                                val v = shortBuffer[i].toInt()
                                byteBuffer[i * 2] = (v and 0xFF).toByte()
                                byteBuffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                            }
                            val b64 = android.util.Base64.encodeToString(
                                byteBuffer, 0, readShorts * 2, android.util.Base64.NO_WRAP
                            )
                            worker.execute {
                                onSendJson(JSONObject().put("type", "voice_chunk").put("audio", b64).put("speaking", true))
                            }
                        }
                    }
                }

                try {
                    record.stop()
                    record.release()
                } catch (_: Exception) {}
                try { acousticEchoCanceler?.release() } catch (_: Exception) {}
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                acousticEchoCanceler = null
                noiseSuppressor = null
                liveAudioRecord = null

            } catch (e: Exception) {
                ui.post { onNote("Live mic error: ${e.message}") }
            }
        }, "LiveVoiceRecordThread").apply {
            isDaemon = true
            start()
        }
    }

    fun stopLiveVoiceStreaming() {
        isLiveStreaming = false
        isLocalSpeaking = false
        val r = liveAudioRecord
        liveAudioRecord = null
        try {
            r?.stop()
            r?.release()
        } catch (_: Exception) {}
        try { acousticEchoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        acousticEchoCanceler = null
        noiseSuppressor = null
        liveRecordThread = null
        disableCommunicationMode()
        ui.post {
            onSetPlayerVolume(1.0f)
            onHideHudDelayed()
        }
        worker.execute {
            onSendJson(JSONObject().put("type", "voice_chunk").put("audio", "").put("speaking", false))
        }
    }

    @Synchronized
    private fun getOrCreateLiveAudioTrack(): AudioTrack {
        var track = liveAudioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            enableCommunicationMode()
            val sampleRate = 16000
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, 12800)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            liveAudioTrack = track
        }
        return track
    }

    fun stopLiveAudioTrack() {
        synchronized(this) {
            audioJitterBuffer.clear()
            try {
                liveAudioEnhancer?.release()
            } catch (_: Exception) {}
            liveAudioEnhancer = null
            try {
                liveAudioTrack?.apply {
                    stop()
                    flush()
                    release()
                }
            } catch (_: Exception) {}
            liveAudioTrack = null
            disableCommunicationMode()
        }
    }

    fun handleIncomingVoiceChunk(base64: String, isSpeaking: Boolean) {
        if (isDestroyed) return
        if (isSpeaking && base64.isNotEmpty()) {
            enableCommunicationMode()
            audioWorker.execute {
                try {
                    val pcmBytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    if (pcmBytes.isNotEmpty()) {
                        audioJitterBuffer.write(pcmBytes, 0, pcmBytes.size)
                        val track = getOrCreateLiveAudioTrack()
                        val available = audioJitterBuffer.availableRead()
                        if (available > 0) {
                            val temp = ByteArray(available)
                            val n = audioJitterBuffer.read(temp, 0, available)
                            if (n > 0) {
                                track.write(temp, 0, n)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            ui.post {
                if (isDestroyed) return@post
                onSetPlayerVolume(0.15f)
                onShowHud("🗣️ Friend speaking…")
                ui.removeCallbacks(incomingSilenceRunnable)
                ui.postDelayed(incomingSilenceRunnable, 1500L)
            }
        } else if (!isSpeaking) {
            audioWorker.execute {
                audioJitterBuffer.clear()
            }
            ui.post {
                if (isDestroyed) return@post
                ui.removeCallbacks(incomingSilenceRunnable)
                onSetPlayerVolume(1.0f)
                onHideHudDelayed()
                disableCommunicationMode()
            }
        }
    }

    fun playIncomingVoice(base64: String) {
        worker.execute {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                val file = File.createTempFile("voice_in_", ".m4a", activity.cacheDir)
                file.writeBytes(bytes)
                ui.post {
                    if (isDestroyed) {
                        file.delete()
                        return@post
                    }
                    onShowHud("🎙️ Friend speaking…")
                    onSetPlayerVolume(0.08f)
                    try {
                        activeVoicePlayer?.release()
                        val mp = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .build()
                            )
                            prepare()
                            setVolume(1.0f, 1.0f)
                            setOnCompletionListener {
                                it.release()
                                if (activeVoicePlayer === it) activeVoicePlayer = null
                                file.delete()
                                onSetPlayerVolume(1.0f)
                                onHideHudDelayed()
                            }
                            setOnErrorListener { it, _, _ ->
                                it.release()
                                if (activeVoicePlayer === it) activeVoicePlayer = null
                                file.delete()
                                onSetPlayerVolume(1.0f)
                                onHideHudDelayed()
                                true
                            }
                            start()
                        }
                        activeVoicePlayer = mp
                    } catch (e: Exception) {
                        onSetPlayerVolume(1.0f)
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stopVoiceRecording(sendAudio: Boolean) {
        if (!isRecording) return
        isRecording = false
        isVoiceLocked = false
        isTrackingSwipeUp = false
        resetMicButtonStates()
        onSetPlayerVolume(1.0f)
        onHideHudDelayed()
        val duration = System.currentTimeMillis() - recordStartTime
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}
        recorder = null

        val file = recordFile
        recordFile = null
        if (file == null || !file.exists()) return

        if (!sendAudio || duration < 350 || file.length() < 300) {
            file.delete()
            if (sendAudio && duration < 350) onNote("Hold to talk (too short)")
            return
        }

        worker.execute {
            try {
                val bytes = file.readBytes()
                file.delete()
                if (bytes.size in 1..300_000) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    onSendJson(JSONObject().put("type", "voice").put("audio", base64))
                    ui.post { onNote("🎙️ Voice note sent!") }
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
    }

    fun reset() {
        audioJitterBuffer.clear()
        if (isVoiceLocked) stopLiveVoiceStreaming()
        stopLiveAudioTrack()
        if (isRecording) stopVoiceRecording(sendAudio = false)
        resetMicButtonStates()
        try { activeVoicePlayer?.release() } catch (_: Exception) {}
        activeVoicePlayer = null
        onSetPlayerVolume(1.0f)
        disableCommunicationMode()
    }

    fun destroy() {
        isDestroyed = true
        reset()
        ui.removeCallbacks(incomingSilenceRunnable)
        worker.shutdownNow()
        audioWorker.shutdownNow()
    }
}

