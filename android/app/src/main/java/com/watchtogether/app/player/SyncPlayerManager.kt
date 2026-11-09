package com.watchtogether.app.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.watchtogether.app.ui.UIUtils
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.math.abs

class SyncPlayerManager(
    private val context: Context,
    private val videoView: PlayerView,
    private val onSendControl: (position: Long, playing: Boolean) -> Unit,
    private val onSendReady: (ready: Boolean, bufferPct: Int) -> Unit,
    private val onNote: (String) -> Unit,
    private val onResetControlsTimer: () -> Unit,
    private val onSetControlsVisible: (Boolean) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    var player: ExoPlayer? = null
        private set

    lateinit var videoLoadingOverlay: LinearLayout
        private set
    lateinit var videoLoadingProgress: ProgressBar
        private set
    lateinit var videoLoadingText: TextView
        private set

    lateinit var bPlay: Button
    lateinit var bPause: Button
    lateinit var seekBar: SeekBar
    lateinit var timeCurrent: TextView
    lateinit var timeTotal: TextView

    var pendingInitialSeek = 0L
    private var hasInitialSeekApplied = false
    private var lastLocalSeekTimestamp = 0L
    private var activeSeekTarget = -1L
    var remoteMime = ""
    var remoteSize = 0L
    var lastSentBufferPct = -1
    private var lastReady: Boolean? = null
    var latest: JSONObject? = null
    var isUserSeeking = false
    private var isDestroyed = false

    // Sync & Hysteresis State: Prevents ping-pong pause/play oscillation
    private var isInitialReadyAchieved = false
    private val stallThresholdMs = 2800L
    private var stallRunnable: Runnable? = null
    private var lastBufferReportTime = 0L

    // DSA: Sliding Window Median Filter for network jitter absorption & stable clock drift estimation
    private val driftFilter = com.watchtogether.app.dsa.DriftJitterFilter(capacity = 7)

    private val debouncedControlRunnable = Runnable {
        if (!isDestroyed && activeSeekTarget >= 0L) {
            val target = activeSeekTarget
            val playing = player?.playWhenReady ?: false
            onSendControl(target, playing)
            onResetControlsTimer()
        }
    }

    val progressUpdater = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val p = player
            if (p != null && !isUserSeeking) {
                val current = p.currentPosition
                val duration = p.duration
                if (duration > 0) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = current.toInt()
                    timeTotal.text = UIUtils.formatTime(duration)
                } else {
                    timeTotal.text = "--:--"
                }
                timeCurrent.text = UIUtils.formatTime(current)
            }
            ui.postDelayed(this, 500)
        }
    }

    fun buildLoadingHud(): LinearLayout {
        videoLoadingOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((22 * density).toInt(), (16 * density).toInt(), (22 * density).toInt(), (16 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#F20D1322"), 18f, Color.parseColor("#38BDF8"), 1)
            elevation = 24f
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        videoLoadingProgress = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (10 * density).toInt()
            }
        }
        videoLoadingText = TextView(context).apply {
            text = "⏳ Loading movie…"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        videoLoadingOverlay.addView(videoLoadingProgress)
        videoLoadingOverlay.addView(videoLoadingText)
        return videoLoadingOverlay
    }

    fun updateLoadingHud(isBuffering: Boolean, text: String = "") {
        ui.post {
            if (isDestroyed || !::videoLoadingOverlay.isInitialized) return@post
            if (isBuffering) {
                videoLoadingOverlay.visibility = View.VISIBLE
                if (text.isNotEmpty()) videoLoadingText.text = text
            } else {
                videoLoadingOverlay.visibility = View.GONE
            }
        }
    }

    fun updatePlayPauseButtons(isPlaying: Boolean) {
        ui.post {
            if (!isDestroyed && ::bPlay.isInitialized && ::bPause.isInitialized) {
                if (isPlaying) {
                    bPlay.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#059669"), 12f, Color.parseColor("#10B981"), 1))
                    bPlay.text = "▶ Playing"
                    bPause.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f))
                    bPause.text = "⏸ Pause"
                } else {
                    bPlay.background = UIUtils.withRipple(UIUtils.gradientBackground(density, Color.parseColor("#2563EB"), Color.parseColor("#4F46E5"), 12f))
                    bPlay.text = "▶ Play"
                    bPause.background = UIUtils.withRipple(UIUtils.roundedBackground(density, Color.parseColor("#DC2626"), 12f, Color.parseColor("#EF4444"), 1))
                    bPause.text = "⏸ Paused"
                }
            }
        }
    }

    fun setupPlayer(
        client: OkHttpClient,
        token: String,
        isHost: Boolean,
        code: String,
        base: String,
        movie: Uri?,
        guestMovie: Uri?
    ) {
        if (player != null) {
            val ready = player?.playbackState == Player.STATE_READY
            if (ready) isInitialReadyAchieved = true
            lastReady = ready
            val pct = if (ready) 100 else (player?.bufferedPercentage ?: 0)
            lastSentBufferPct = pct
            onSendReady(ready, pct)
            return
        }

        isInitialReadyAchieved = false
        stallRunnable?.let { ui.removeCallbacks(it) }
        stallRunnable = null

        val httpFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        val factory = DefaultDataSource.Factory(context, httpFactory)
        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(factory, extractorsFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs (15s reservoir prevents cellular starvation)
                50_000, // maxBufferMs (up to 50s continuous prefetch)
                2_500,  // bufferForPlaybackMs (2.5s runway before initial playback)
                3_500   // bufferForPlaybackAfterRebufferMs (3.5s cushion after any rebuffer)
            )
            .setBackBuffer(10_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()
        p.setAudioAttributes(audioAttributes, true)
        player = p
        videoView.player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        stallRunnable?.let { ui.removeCallbacks(it) }
                        stallRunnable = null

                        isInitialReadyAchieved = true
                        if (lastReady != true) {
                            lastReady = true
                            lastSentBufferPct = 100
                            onSendReady(true, 100)
                        }

                        updateLoadingHud(false)
                        if (!hasInitialSeekApplied && pendingInitialSeek > 1500L) {
                            hasInitialSeekApplied = true
                            val target = pendingInitialSeek
                            pendingInitialSeek = 0L
                            p.seekTo(target)
                        } else if (!hasInitialSeekApplied) {
                            hasInitialSeekApplied = true
                        }
                        val isPlaying = player?.playWhenReady == true
                        updatePlayPauseButtons(isPlaying)
                        onNote(if (isPlaying) "▶ Playing in sync" else "⏸ Paused (Both in sync)")
                        if (isPlaying) onResetControlsTimer() else onSetControlsVisible(true)
                    }
                    Player.STATE_BUFFERING -> {
                        val pct = p.bufferedPercentage
                        if (!isInitialReadyAchieved) {
                            // Initial startup: report ready=false so server waits for both peers
                            if (lastReady != false) {
                                lastReady = false
                                lastSentBufferPct = pct
                                onSendReady(false, pct)
                            }
                            val msg = if (pct > 0) "⏳ Buffering ($pct%)…" else "⏳ Preparing movie…"
                            updateLoadingHud(true, msg)
                            onNote(msg)
                        } else {
                            // Runtime rebuffering: apply stall hysteresis. Do NOT immediately pause peer!
                            // Only send ready=false if stall persists continuously for stallThresholdMs.
                            if (stallRunnable == null) {
                                stallRunnable = Runnable {
                                    if (!isDestroyed && player?.playbackState == Player.STATE_BUFFERING) {
                                        lastReady = false
                                        val pPct = player?.bufferedPercentage ?: 0
                                        lastSentBufferPct = pPct
                                        onSendReady(false, pPct)
                                        val msg = if (pPct > 0) "⏳ Buffering ($pPct%)…" else "⏳ Buffering movie…"
                                        updateLoadingHud(true, msg)
                                        onNote(msg)
                                    }
                                    stallRunnable = null
                                }
                                ui.postDelayed(stallRunnable!!, stallThresholdMs)
                            }
                            ui.postDelayed({
                                if (!isDestroyed && player?.playbackState == Player.STATE_BUFFERING) {
                                    val currentPct = player?.bufferedPercentage ?: 0
                                    val msg = if (currentPct > 0) "⏳ Buffering ($currentPct%)…" else "⏳ Buffering movie…"
                                    updateLoadingHud(true, msg)
                                }
                            }, 800L)
                        }
                    }
                    Player.STATE_ENDED -> control(p.currentPosition, false, true)
                    Player.STATE_IDLE -> {
                        stallRunnable?.let { ui.removeCallbacks(it) }
                        stallRunnable = null
                        updateLoadingHud(false)
                        onNote("Player idle")
                    }
                }
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (p.playbackState == Player.STATE_BUFFERING) {
                    val pct = p.bufferedPercentage
                    val now = android.os.SystemClock.elapsedRealtime()
                    // Throttle WebSocket updates: only report if in declared stall or initial load, and only on significant delta
                    if (lastReady == false || !isInitialReadyAchieved) {
                        if (kotlin.math.abs(pct - lastSentBufferPct) >= 15 || (now - lastBufferReportTime > 1500L && pct != lastSentBufferPct)) {
                            lastBufferReportTime = now
                            lastSentBufferPct = pct
                            onSendReady(false, pct)
                            val msg = if (pct > 0) "⏳ Buffering ($pct%)…" else "⏳ Buffering movie…"
                            updateLoadingHud(true, msg)
                            onNote(msg)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                stallRunnable?.let { ui.removeCallbacks(it) }
                stallRunnable = null
                updateLoadingHud(false)
                lastReady = false
                onSendReady(false, 0)
                onNote("Video error: ${error.errorCodeName}. Details: ${error.message}")
            }
        })

        val ext = when {
            remoteMime.contains("matroska", ignoreCase = true) || remoteMime.endsWith("mkv", ignoreCase = true) -> ".mkv"
            remoteMime.contains("mp4", ignoreCase = true) -> ".mp4"
            remoteMime.contains("webm", ignoreCase = true) -> ".webm"
            else -> ""
        }
        val mediaUri = if (guestMovie != null) {
            guestMovie
        } else if (isHost) {
            movie ?: run {
                onNote("⚠️ Local movie not loaded. Please select a movie file.")
                return
            }
        } else {
            Uri.parse("$base/rooms/$code/movie$ext?token=$token")
        }
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .apply {
                if (!isHost && guestMovie == null && remoteMime.isNotEmpty()) {
                    setMimeType(remoteMime)
                }
            }
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        latest?.let { applyState(it, isHost, code, guestMovie != null) }
        ui.removeCallbacks(progressUpdater)
        ui.post(progressUpdater)
    }

    fun applyState(data: JSONObject, isHost: Boolean, code: String, hasGuestLocalMovie: Boolean) {
        val p = player ?: return
        val position = data.getLong("position")
        val playing = data.getBoolean("playing")
        val guestConnected = data.optBoolean("guestConnected", false)
        val guestReady = data.optBoolean("guestReady", false)
        val guestBufferPct = data.optInt("guestBufferPct", 0)

        val m = data.optString("mime")
        if (m.isNotEmpty()) remoteMime = m
        val s = data.optLong("size", 0L)
        if (s > 0L) remoteSize = s

        p.playWhenReady = playing
        updatePlayPauseButtons(playing)

        if (isHost) {
            if (!guestConnected) {
                updateLoadingHud(false)
                onNote("⏳ Solo mode · Waiting for friend to join (${code})…")
            } else if (!guestReady) {
                val fMsg = if (guestBufferPct > 0) "⏳ Friend is buffering ($guestBufferPct%)…" else "⏳ Friend joined! Buffering movie…"
                updateLoadingHud(true, fMsg)
                onNote(fMsg)
            } else {
                updateLoadingHud(p.playbackState == Player.STATE_BUFFERING, "⏳ Buffering movie…")
                if (playing) {
                    onNote("▶ Watching together in sync")
                } else {
                    onNote("⏸ Paused (Both in sync)")
                }
            }
        } else {
            val hostConnected = data.optBoolean("hostConnected", true)
            if (!hostConnected) {
                updateLoadingHud(true, "⏳ Host temporarily reconnecting…")
                onNote("⏳ Host temporarily reconnecting… playback paused")
            } else if (p.playbackState == Player.STATE_BUFFERING) {
                val pct = p.bufferedPercentage
                val gMsg = if (pct > 0) "⏳ Buffering ($pct%)…" else "⏳ Buffering movie…"
                updateLoadingHud(true, gMsg)
                onNote(gMsg)
            } else if (!guestReady) {
                val pct = p.bufferedPercentage
                val gMsg = if (pct > 0) "⏳ Buffering ($pct%)…" else "⏳ Preparing movie…"
                updateLoadingHud(true, gMsg)
                onNote(gMsg)
            } else {
                updateLoadingHud(false)
                if (playing) {
                    onNote(if (hasGuestLocalMovie) "▶ Watching in sync (Local 0 MB Data Mode)" else "▶ Watching together in sync")
                } else {
                    onNote("⏸ Paused by host/friend")
                }
            }
        }

        val timeSinceLocalSeek = SystemClock.elapsedRealtime() - lastLocalSeekTimestamp
        val isLocalSeekActive = isUserSeeking || timeSinceLocalSeek < 2000L

        if (playing) {
            onResetControlsTimer()
            if (p.playbackState == Player.STATE_READY) {
                // IMPORTANT: The Host is the master timeline clock (source of truth).
                // The Host must NEVER adjust playback speed or seek against the server's delayed echo!
                // Only the Guest synchronizes against the Host's timeline.
                if (!isLocalSeekActive && !isHost) {
                    val rawDrift = position - p.currentPosition
                    val drift = if (abs(rawDrift) > 6000) {
                        driftFilter.reset()
                        rawDrift
                    } else {
                        driftFilter.addSample(rawDrift)
                    }
                    when {
                        abs(drift) > 6000 -> {
                            // Major desync (> 6s) - jump to host position
                            p.seekTo(position)
                            p.playbackParameters = PlaybackParameters(1.0f)
                        }
                        drift > 2500 -> {
                            // Behind by 2.5s - 6s: catch up at 1.10x smoothly without hard seek
                            p.playbackParameters = PlaybackParameters(1.10f)
                        }
                        drift < -2500 -> {
                            // Ahead by 2.5s - 6s: slow down to 0.90x smoothly
                            p.playbackParameters = PlaybackParameters(0.90f)
                        }
                        drift > 750 -> {
                            // Behind by 750ms - 2500ms: gentle 1.04x speedup
                            p.playbackParameters = PlaybackParameters(1.04f)
                        }
                        drift < -750 -> {
                            // Ahead by 750ms - 2500ms: gentle 0.96x slowdown
                            p.playbackParameters = PlaybackParameters(0.96f)
                        }
                        else -> {
                            // In sync (within ±750ms deadband): normal 1.0x speed
                            p.playbackParameters = PlaybackParameters(1.0f)
                        }
                    }
                } else if (isHost) {
                    p.playbackParameters = PlaybackParameters(1.0f)
                }
            } else if (!hasInitialSeekApplied) {
                driftFilter.reset()
                pendingInitialSeek = position
            }
        } else {
            onSetControlsVisible(true)
            p.playbackParameters = PlaybackParameters(1.0f)
            if (!isLocalSeekActive) {
                if (p.playbackState == Player.STATE_READY && abs(p.currentPosition - position) > 2500) {
                    p.seekTo(position)
                } else if (!hasInitialSeekApplied && p.playbackState != Player.STATE_READY) {
                    pendingInitialSeek = position
                }
            }
        }
    }

    fun seekRelative(deltaMs: Long, isConnected: Boolean) {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        val duration = p.duration
        val basePos = if (now - lastLocalSeekTimestamp < 800L && activeSeekTarget >= 0L) {
            activeSeekTarget
        } else {
            p.currentPosition
        }
        val target = if (duration > 0) (basePos + deltaMs).coerceIn(0L, duration) else maxOf(0L, basePos + deltaMs)
        activeSeekTarget = target
        lastLocalSeekTimestamp = now
        driftFilter.reset()
        p.seekTo(target)
        if (isConnected) {
            ui.removeCallbacks(debouncedControlRunnable)
            ui.postDelayed(debouncedControlRunnable, 120L)
        }
    }

    fun seekToExplicit(target: Long, isConnected: Boolean) {
        val p = player ?: return
        lastLocalSeekTimestamp = SystemClock.elapsedRealtime()
        activeSeekTarget = target
        driftFilter.reset()
        p.seekTo(target)
        ui.removeCallbacks(debouncedControlRunnable)
        if (isConnected) {
            onSendControl(target, p.playWhenReady)
            onResetControlsTimer()
        }
    }

    fun control(position: Long, playing: Boolean, isConnected: Boolean) {
        driftFilter.reset()
        ui.removeCallbacks(debouncedControlRunnable)
        if (isConnected && player != null) {
            onSendControl(position, playing)
            onResetControlsTimer()
        }
    }

    fun setVolume(volume: Float) {
        player?.volume = volume
    }

    fun release() {
        stallRunnable?.let { ui.removeCallbacks(it) }
        stallRunnable = null
        ui.removeCallbacks(progressUpdater)
        ui.removeCallbacks(debouncedControlRunnable)
        driftFilter.reset()
        lastLocalSeekTimestamp = 0L
        activeSeekTarget = -1L
        hasInitialSeekApplied = false
        isInitialReadyAchieved = false
        lastSentBufferPct = -1
        lastReady = null
        latest = null
        pendingInitialSeek = 0L
        lastBufferReportTime = 0L
        updateLoadingHud(false)
        player?.release()
        player = null
        videoView.player = null
    }

    fun destroy() {
        isDestroyed = true
        release()
    }
}

