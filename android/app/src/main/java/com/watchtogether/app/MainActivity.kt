package com.watchtogether.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : Activity() {
    companion object {
        const val DEFAULT_SERVER = "https://watchtogether-relay.onrender.com"
        const val DEFAULT_CREATOR_KEY = "lbj1t0uyEHOgRhuDXI4r2OjLvNM9fpZ8vqNHzDpsmeo"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(8)
    private val videoWorker = Executors.newFixedThreadPool(8)
    private val audioWorker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private lateinit var mainLayout: LinearLayout
    private lateinit var setupScrollView: ScrollView
    private lateinit var hostTab: Button
    private lateinit var joinTab: Button
    private lateinit var hostContainer: LinearLayout
    private lateinit var joinContainer: LinearLayout
    private lateinit var sessionHeader: LinearLayout
    private lateinit var roomCodeBadge: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var status: TextView
    private lateinit var server: EditText
    private lateinit var inviteInput: EditText
    private lateinit var creatorKey: EditText
    private var generation = 0
    private lateinit var fileLabel: TextView

    // Modern Player Components
    private lateinit var playerContainer: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var emojiContainer: FrameLayout
    private lateinit var rippleContainer: FrameLayout
    private lateinit var leftRipple: TextView
    private lateinit var rightRipple: TextView
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudText: TextView
    private lateinit var touchOverlay: View
    private lateinit var floatingMicBtn: TextView
    private lateinit var controlsGroup: LinearLayout
    private lateinit var reactionBar: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var openMicBtn: TextView
    private lateinit var seekbarRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var bottomControls: LinearLayout
    private lateinit var bPlay: Button
    private lateinit var bPause: Button

    // In-App Friend Presence & Direct Invites
    private var myUserId = ""
    private var myUserName = ""
    private var lobbySocket: WebSocket? = null
    private val lobbyReconnectTask = Runnable { connectLobby() }
    private var onlineFriends = mutableListOf<JSONObject>()
    private lateinit var userBadge: TextView
    private lateinit var friendsCard: LinearLayout
    private lateinit var onlineFriendsContainer: LinearLayout
    private lateinit var friendsHeaderBtn: Button

    // Guest Video Sync & Metadata
    private var pendingInitialSeek = 0L
    private var remoteMime = ""
    private var remoteSize = 0L

    // Push-to-Talk (Walkie-Talkie) State
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStartTime = 0L
    private var isRecording = false
    private var isVoiceLocked = false
    private var micTouchStartY = 0f
    private var isTrackingSwipeUp = false
    private var activeVoicePlayer: MediaPlayer? = null

    // Idea 2: Smart Open-Mic Live Voice Streaming & VAD
    @Volatile private var isLiveStreaming = false
    @Volatile private var isLocalSpeaking = false
    private var liveRecordThread: Thread? = null
    private var liveAudioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var liveAudioTrack: AudioTrack? = null
    private var liveAudioEnhancer: LoudnessEnhancer? = null
    private val incomingSilenceRunnable = Runnable {
        if (!destroyed) {
            player?.volume = 1.0f
            hideHudDelayed()
        }
    }

    private var isUserSeeking = false
    private var controlsVisible = true

    // Robust Gesture Touch State (Bypasses Android 14 GestureDetector issues)
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isVerticalDragging = false
    private var dragGestureType = 0 // 1: brightness, 2: volume
    private var initialScreenBrightness = 0.5f
    private var initialAudioVolume = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val doubleTapTimeout = 280L
    private val singleTapRunnable = Runnable {
        if (!destroyed) {
            toggleControlsVisibility()
        }
    }

    private val hideControlsRunnable = Runnable {
        if (!destroyed && player?.playWhenReady == true) {
            setControlsVisible(false)
        }
    }

    private val hideHudRunnable = Runnable {
        if (!destroyed && ::hudContainer.isInitialized) {
            hudContainer.animate().alpha(0f).setDuration(250).withEndAction {
                hudContainer.visibility = View.GONE
            }.start()
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!destroyed && connected && player != null) {
                val p = player
                if (p != null && !isUserSeeking) {
                    val duration = p.duration.coerceAtLeast(0L)
                    val current = p.currentPosition.coerceAtLeast(0L)
                    val buffered = p.bufferedPosition.coerceAtLeast(0L)
                    if (duration > 0) {
                        seekBar.max = duration.toInt()
                        seekBar.progress = current.toInt()
                        seekBar.secondaryProgress = buffered.toInt()
                        timeCurrent.text = formatTime(current)
                        timeTotal.text = formatTime(duration)
                    } else {
                        timeCurrent.text = formatTime(current)
                        timeTotal.text = "--:--"
                    }
                }
            }
            if (!destroyed && connected) {
                ui.postDelayed(this, 500)
            }
        }
    }

    private var movie: Uri? = null
    @Volatile private var movieChannel: FileChannel? = null
    @Volatile private var moviePfd: ParcelFileDescriptor? = null
    private var size = 0L
    private var mime = "video/mp4"
    private var reconnectAttempts = 0
    private var autoCreateAfterPick = false
    private val reconnectTask = Runnable {
        if (!destroyed && !connected && token.isNotEmpty()) {
            note("Reconnecting (attempt $reconnectAttempts/5)…")
            connect()
        }
    }
    private var player: ExoPlayer? = null
    @Volatile private var socket: WebSocket? = null
    private var host = false
    private var connected = false
    private var connecting = false
    private var code = ""
    private var token = ""
    private var base = ""
    private var invite = ""
    private var latest: JSONObject? = null
    private var lastReady: Boolean? = null
    private var destroyed = false
    private val tick = object : Runnable {
        override fun run() {
            if (host && connected) player?.let { send(JSONObject().put("type", "tick").put("position", it.currentPosition)) }
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            fitsSystemWindows = true
        }

        // Active Session Header (Shown only during watching)
        sessionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = roundedBackground(Color.parseColor("#1E293B"), 10f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        val topBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        roomCodeBadge = TextView(this).apply {
            text = "Room"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(Color.parseColor("#334155"), 6f)
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        friendsHeaderBtn = styledButton("👥 Friends", Color.parseColor("#059669")) {
            showFriendsInviteDialog()
        }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        val shareHeaderBtn = styledButton("📤 Invite", Color.parseColor("#6366F1")) {
            if (invite.isNotEmpty()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$base|$invite")
            }, "Invite your friend"))
        }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        val leaveBtn = styledButton("✕ Leave", Color.parseColor("#DC2626")) { leave() }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topBtnRow.addView(roomCodeBadge)
        topBtnRow.addView(friendsHeaderBtn)
        topBtnRow.addView(shareHeaderBtn)
        topBtnRow.addView(leaveBtn)
        sessionHeader.addView(topBtnRow)

        sessionStatus = TextView(this).apply {
            text = "Connecting…"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * density).toInt()
            }
        }
        sessionHeader.addView(sessionStatus)
        mainLayout.addView(sessionHeader)

        // Setup ScrollView (Holds the 3-step pairing flow)
        setupScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = true
        }
        val setupContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val titleText = TextView(this).apply {
            text = "WatchTogether"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        userBadge = TextView(this).apply {
            val name = getUserName()
            text = "👤 ${if (name.isNotEmpty()) name else "Set Name"} ✏️"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedBackground(Color.parseColor("#1E293B"), 12f, Color.parseColor("#0284C7"), 1)
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            setOnClickListener { showEditNameDialog(initial = false) }
        }
        titleRow.addView(titleText)
        titleRow.addView(userBadge)
        setupContent.addView(titleRow)

        val subtitleText = TextView(this).apply {
            text = "Pair with a friend in 3 easy steps"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        setupContent.addView(subtitleText)

        // Online Friends Presence Card (In-App Direct Invite)
        friendsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#1E293B"), 12f, Color.parseColor("#334155"), 1)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (10 * density).toInt(), 0, 0)
            }
        }
        val friendsCardTitle = TextView(this).apply {
            text = "👥 Friends Online (Tap to Invite Directly)"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
        }
        friendsCard.addView(friendsCardTitle)

        val friendsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (6 * density).toInt()
            }
        }
        onlineFriendsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        friendsScroll.addView(onlineFriendsContainer)
        friendsCard.addView(friendsScroll)
        setupContent.addView(friendsCard)

        // 2-Way Role Switcher
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(Color.parseColor("#1E293B"), 12f)
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (14 * density).toInt(), 0, (10 * density).toInt())
            }
        }
        hostTab = Button(this).apply {
            text = "🎬 Host Movie"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { selectTab(true) }
        }
        joinTab = Button(this).apply {
            text = "🤝 Join Friend"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { selectTab(false) }
        }
        tabRow.addView(hostTab)
        tabRow.addView(joinTab)
        setupContent.addView(tabRow)

        // HOST CONTAINER
        hostContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Host 1-Tap Quick Action Card
        val quickCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = roundedBackground(Color.parseColor("#1E293B"), 14f, Color.parseColor("#10B981"), 2)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (4 * density).toInt(), 0, (10 * density).toInt())
            }
        }
        val quickTitle = TextView(this).apply {
            text = "⚡ Instant Room Setup"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val quickDesc = TextView(this).apply {
            text = "Tap below to choose your movie and instantly launch your watch party."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
        }
        fileLabel = TextView(this).apply {
            text = "📁 No movie selected yet (will prompt on tap)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        val processCreateBtn = styledButton("🚀 Process & Create Room", Color.parseColor("#10B981")) {
            processCreateRoom()
        }.apply {
            textSize = 15f
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }
        quickCard.addView(quickTitle)
        quickCard.addView(quickDesc)
        quickCard.addView(fileLabel)
        quickCard.addView(processCreateBtn)
        hostContainer.addView(quickCard)

        // Pre-configured Server & Security Settings Card
        val settingsCard = stepCard("⚙", "Relay & Security (Configured ✓)", "Server origin and host access credentials")
        val prefServer = getPreferences(0).getString("server", DEFAULT_SERVER)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        val savedServer = if (prefServer.contains("trycloudflare.com")) DEFAULT_SERVER else prefServer
        server = EditText(this).apply {
            hint = "Relay Server HTTPS origin"
            setSingleLine(true)
            setText(savedServer)
            inputType = 17
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = roundedBackground(Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        val savedKey = getPreferences(0).getString("creator_key", DEFAULT_CREATOR_KEY)?.takeIf { it.isNotBlank() } ?: DEFAULT_CREATOR_KEY
        creatorKey = EditText(this).apply {
            hint = "Host Access Key (32+ chars)"
            setSingleLine(true)
            setText(savedKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = roundedBackground(Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        val pickMovieBtn = styledButton("📁 Browse / Change Movie", Color.parseColor("#2563EB")) {
            if (!connected && !connecting) pickMovie(autoCreate = false)
        }
        settingsCard.addView(server)
        settingsCard.addView(spacer(8))
        settingsCard.addView(creatorKey)
        settingsCard.addView(spacer(8))
        settingsCard.addView(pickMovieBtn)
        hostContainer.addView(settingsCard)
        setupContent.addView(hostContainer)

        // JOIN 3-STEP CONTAINER
        joinContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Join Step 1: Friend's Invite
        val joinStep1 = stepCard("1", "Friend's Invite", "Enter or paste the invite link your friend sent")
        inviteInput = EditText(this).apply {
            hint = "server|room.token"
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = roundedBackground(Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            val lastInvite = getPreferences(0).getString("last_invite", "") ?: ""
            if (lastInvite.isNotEmpty()) {
                setText(lastInvite)
            }
        }
        val pasteBtn = styledButton("📋 Paste from Clipboard", Color.parseColor("#334155")) {
            try {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!clip.isNullOrEmpty()) {
                    inviteInput.setText(clip)
                    note("Invite pasted! Tap 'Connect & Pair' below.")
                } else {
                    note("Clipboard is empty.")
                }
            } catch (e: Exception) { note("Cannot read clipboard") }
        }
        joinStep1.addView(inviteInput)
        joinStep1.addView(spacer(8))
        joinStep1.addView(pasteBtn)
        joinContainer.addView(joinStep1)

        // Join Step 2: Connect & Pair
        val joinStep2 = stepCard("2", "Connect & Pair", "Redeem invitation and establish real-time connection")
        val joinBtn = styledButton("⚡ Connect & Pair", Color.parseColor("#2563EB")) {
            if (!connected && !connecting) joinRoom()
        }
        joinStep2.addView(joinBtn)
        joinContainer.addView(joinStep2)

        // Join Step 3: Watch
        val joinStep3 = stepCard("3", "Watch in Sync", "Video streams directly from your friend in real-time")
        val joinHelp = TextView(this).apply {
            text = "No movie file needed on your phone. Playback stays in lockstep automatically."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }
        joinStep3.addView(joinHelp)
        joinContainer.addView(joinStep3)
        setupContent.addView(joinContainer)

        // Status Card
        status = TextView(this).apply {
            text = "Ready · Step 1: Choose a movie to begin"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = roundedBackground(Color.parseColor("#0F172A"), 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        }
        setupContent.addView(status)

        setupScrollView.addView(setupContent)
        mainLayout.addView(setupScrollView)

        // Modern Video Player Container with Overlays
        playerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
        }

        // 0. ExoPlayer View
        video = PlayerView(this).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        playerContainer.addView(video)

        // 1. Gesture Overlay (Directly over video to catch all swipe, double-tap & tap gestures)
        touchOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }
        touchOverlay.setOnTouchListener { v, event ->
            val density = resources.displayMetrics.density
            val w = v.width.toFloat()
            val h = v.height.toFloat()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartTime = System.currentTimeMillis()
                    isVerticalDragging = false
                    dragGestureType = 0
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - touchStartX
                    val dy = touchStartY - event.y // Positive when dragging UP
                    val absDx = abs(dx)
                    val absDy = abs(dy)

                    // Detect vertical swipe when movement > 12dp and predominantly vertical
                    if (!isVerticalDragging && absDy > 12f * density && absDy > absDx * 1.1f) {
                        isVerticalDragging = true
                        dragGestureType = if (touchStartX < w / 2f) 1 else 2 // 1: brightness, 2: volume

                        if (dragGestureType == 1) {
                            val lp = window.attributes
                            initialScreenBrightness = if (lp.screenBrightness < 0f) {
                                try {
                                    android.provider.Settings.System.getInt(
                                        contentResolver,
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                                    ) / 255f
                                } catch (_: Exception) { 0.5f }
                            } else lp.screenBrightness
                        } else {
                            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                            initialAudioVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }

                    if (isVerticalDragging && h > 0) {
                        val deltaNormalized = (dy / (h * 0.65f)).coerceIn(-1.0f, 1.0f)
                        if (dragGestureType == 1) {
                            // Adjust Brightness
                            val targetB = (initialScreenBrightness + deltaNormalized).coerceIn(0.02f, 1.0f)
                            val lp = window.attributes
                            lp.screenBrightness = targetB
                            window.attributes = lp
                            showHud("🔆 Brightness: ${(targetB * 100).toInt()}%")
                        } else {
                            // Adjust Volume
                            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val deltaSteps = (deltaNormalized * maxVol).toInt()
                            val targetVol = (initialAudioVolume + deltaSteps).coerceIn(0, maxVol)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            val pct = if (maxVol > 0) (targetVol * 100) / maxVol else 0
                            showHud(if (targetVol == 0) "🔇 Muted" else "🔊 Volume: $pct%")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val totalTime = now - touchStartTime
                    val totalDist = abs(event.x - touchStartX) + abs(event.y - touchStartY)

                    if (isVerticalDragging) {
                        isVerticalDragging = false
                        hideHudDelayed()
                    } else if (totalDist < 25f * density && totalTime < 350) {
                        // Tap detected: check for double-tap vs single-tap
                        val timeSinceLastTap = now - lastTapTime
                        val distFromLastTap = abs(event.x - lastTapX) + abs(event.y - lastTapY)

                        if (timeSinceLastTap < doubleTapTimeout && distFromLastTap < 50f * density) {
                            // DOUBLE TAP!
                            ui.removeCallbacks(singleTapRunnable)
                            lastTapTime = 0L
                            if (event.x < w / 2f) {
                                seekRelative(-10000L)
                                showRipple(false)
                                showHud("« 10s")
                            } else {
                                seekRelative(10000L)
                                showRipple(true)
                                showHud("10s »")
                            }
                            hideHudDelayed()
                        } else {
                            // FIRST TAP: wait for doubleTapTimeout before toggling controls
                            lastTapTime = now
                            lastTapX = event.x
                            lastTapY = event.y
                            ui.removeCallbacks(singleTapRunnable)
                            ui.postDelayed(singleTapRunnable, doubleTapTimeout)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isVerticalDragging) {
                        isVerticalDragging = false
                        hideHudDelayed()
                    }
                    true
                }
                else -> false
            }
        }
        playerContainer.addView(touchOverlay)

        // 2. Double-Tap Ripple Overlay Container
        rippleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        leftRipple = TextView(this).apply {
            text = "« 10s"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(Color.parseColor("#CC1E293B"), 18f, Color.parseColor("#3B82F6"), 1)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = (28 * density).toInt()
            }
        }
        rightRipple = TextView(this).apply {
            text = "10s »"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(Color.parseColor("#CC1E293B"), 18f, Color.parseColor("#3B82F6"), 1)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = (28 * density).toInt()
            }
        }
        rippleContainer.addView(leftRipple)
        rippleContainer.addView(rightRipple)
        playerContainer.addView(rippleContainer)

        // 3. Floating Emoji Overlay Container
        emojiContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        playerContainer.addView(emojiContainer)

        // 4. Brightness & Volume HUD Container
        hudContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (16 * density).toInt()
            }
            isClickable = false
            isFocusable = false
        }
        hudText = TextView(this).apply {
            text = "HUD"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(Color.parseColor("#E60F172A"), 14f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
        }
        hudContainer.addView(hudText)
        playerContainer.addView(hudContainer)

        // 5. Floating Quick Walkie-Talkie Button (Positioned at bottom-right for natural thumb reach)
        floatingMicBtn = TextView(this).apply {
            text = "🎙️"
            textSize = 17f
            gravity = Gravity.CENTER
            background = roundedBackground(Color.parseColor("#CC059669"), 18f, Color.parseColor("#34D399"), 1)
            layoutParams = FrameLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = (72 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            elevation = 15f
            setOnClickListener {
                if (isVoiceLocked) {
                    isVoiceLocked = false
                    stopLiveVoiceStreaming()
                    resetMicButtonStates()
                    note("🎙️ Mic muted")
                } else {
                    isVoiceLocked = true
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
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
                    note("🟢 Open Mic active! Speak freely. Tap to mute.")
                }
            }
            setOnLongClickListener {
                performClick()
                true
            }
        }
        playerContainer.addView(floatingMicBtn)

        mainLayout.addView(playerContainer)

        // Unified Controls Group (Reactions + Seekbar + Buttons)
        controlsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = roundedBackground(Color.parseColor("#E61E293B"), 12f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
        }

        // 1. Live Floating Emoji Bar & Push-to-Talk Mic & Open Mic Toggle
        reactionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val emojis = listOf("😂", "❤️", "🍿", "🔥", "👏")
        for (emoji in emojis) {
            val emojiBtn = TextView(this).apply {
                text = emoji
                textSize = 17f
                gravity = Gravity.CENTER
                background = roundedBackground(Color.parseColor("#334155"), 14f)
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                setOnClickListener {
                    animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()
                    send(JSONObject().put("type", "reaction").put("emoji", emoji))
                    resetControlsTimer()
                }
            }
            reactionBar.addView(emojiBtn)
        }

        // Push-to-Talk Walkie-Talkie Button (Hold to talk, swipe up 18dp to lock)
        micBtn = TextView(this).apply {
            text = "🎙️ Talk"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.parseColor("#059669"), 14f)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
            setOnTouchListener { v, event ->
                resetControlsTimer()
                handleMicTouchEvent(v, event)
            }
        }
        reactionBar.addView(micBtn)

        // Dedicated Open Mic Toggle Button (1-tap lock hands-free)
        openMicBtn = TextView(this).apply {
            text = "🔓 Open"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.parseColor("#334155"), 14f)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (2 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
            setOnClickListener {
                resetControlsTimer()
                if (isVoiceLocked) {
                    isVoiceLocked = false
                    stopLiveVoiceStreaming()
                    resetMicButtonStates()
                    note("🎙️ Mic muted")
                } else {
                    isVoiceLocked = true
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
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
                    note("🟢 Open Mic active! Speak freely. Tap to mute.")
                }
            }
        }
        reactionBar.addView(openMicBtn)
        controlsGroup.addView(reactionBar)

        // 2. Interactive Seekbar / Scrubber Row
        seekbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        timeCurrent = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
            progressTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
            secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#64748B"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#334155"))
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#60A5FA"))
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeCurrent.text = formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    isUserSeeking = true
                    ui.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    isUserSeeking = false
                    control(sb.progress.toLong(), player?.playWhenReady ?: false)
                    resetControlsTimer()
                }
            })
        }
        timeTotal = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        seekbarRow.addView(timeCurrent)
        seekbarRow.addView(seekBar)
        seekbarRow.addView(timeTotal)
        controlsGroup.addView(seekbarRow)

        // 3. Bottom Playback Controls
        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val ctrlWeightParams = {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (2 * density).toInt()
                marginEnd = (2 * density).toInt()
            }
        }
        val b30 = styledButton("−30s", Color.parseColor("#334155")) {
            seekRelative(-30000L)
        }.apply {
            layoutParams = ctrlWeightParams()
            textSize = 12f
            setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
        }
        bPlay = styledButton("▶ Play", Color.parseColor("#2563EB")) {
            control(player?.currentPosition ?: 0L, true)
        }.apply {
            layoutParams = ctrlWeightParams()
            textSize = 12f
            setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
        }
        bPause = styledButton("⏸ Pause", Color.parseColor("#475569")) {
            control(player?.currentPosition ?: 0L, false)
        }.apply {
            layoutParams = ctrlWeightParams()
            textSize = 12f
            setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
        }
        val bPlus30 = styledButton("+30s", Color.parseColor("#334155")) {
            seekRelative(30000L)
        }.apply {
            layoutParams = ctrlWeightParams()
            textSize = 12f
            setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
        }
        bottomControls.addView(b30)
        bottomControls.addView(bPlay)
        bottomControls.addView(bPause)
        bottomControls.addView(bPlus30)
        controlsGroup.addView(bottomControls)

        mainLayout.addView(controlsGroup)

        setContentView(mainLayout)
        selectTab(true)
        ui.post(tick)

        myUserId = getUserId()
        myUserName = getUserName()
        updateOnlineFriendsUI()
        if (myUserName.isEmpty()) {
            ui.postDelayed({ showEditNameDialog(initial = true) }, 500)
        } else {
            ui.postDelayed({ connectLobby() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        if (token.isNotEmpty() && !destroyed && (socket == null || !connected) && !connecting) {
            note("Reconnecting to movie…")
            connect()
        } else if (!connected && !connecting) {
            try {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (clip != null && clip.contains('|') && clip.contains('.')) {
                    if (inviteInput.text.isEmpty() || inviteInput.text.toString() != clip) {
                        inviteInput.setText(clip)
                        selectTab(false)
                        note("📋 Detected friend's invite in clipboard! Tap 'Connect & Pair' to join.")
                    }
                }
            } catch (_: Exception) {}
        }
        if (getUserName().isNotEmpty() && (lobbySocket == null || !isLobbyConnected)) {
            connectLobby()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (connected && player != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val params = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            sessionHeader.visibility = View.GONE
            controlsGroup.visibility = View.GONE
            mainLayout.setPadding(0, 0, 0, 0)
            playerContainer.layoutParams = LinearLayout.LayoutParams(-1, -1)
        } else {
            playerContainer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            updateScreenMode()
        }
    }

    override fun onStop() {
        super.onStop()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Keep running uninterrupted in PiP
        } else {
            if (isVoiceLocked) {
                stopLiveVoiceStreaming()
                resetMicButtonStates()
            }
        }
    }

    private fun resetMicButtonStates() {
        ui.post {
            isVoiceLocked = false
            isTrackingSwipeUp = false
            if (::micBtn.isInitialized) {
                micBtn.text = "🎙️ Talk"
                micBtn.background = roundedBackground(Color.parseColor("#059669"), 14f)
                micBtn.scaleX = 1f
                micBtn.scaleY = 1f
            }
            if (::openMicBtn.isInitialized) {
                openMicBtn.text = "🔓 Open"
                openMicBtn.background = roundedBackground(Color.parseColor("#334155"), 14f)
                openMicBtn.scaleX = 1f
                openMicBtn.scaleY = 1f
            }
            if (::floatingMicBtn.isInitialized) {
                floatingMicBtn.text = "🎙️"
                floatingMicBtn.background = roundedBackground(Color.parseColor("#CC059669"), 18f, Color.parseColor("#34D399"), 1)
                floatingMicBtn.scaleX = 1f
                floatingMicBtn.scaleY = 1f
            }
        }
    }

    private fun updateMicButtonsState(speaking: Boolean) {
        ui.post {
            if (!isVoiceLocked) return@post
            val density = resources.displayMetrics.density
            if (speaking) {
                if (::micBtn.isInitialized) {
                    micBtn.text = "🗣️ Speaking…"
                    micBtn.background = roundedBackground(Color.parseColor("#059669"), 14f, Color.parseColor("#34D399"), 2)
                }
                if (::openMicBtn.isInitialized) {
                    openMicBtn.text = "🗣️ Live"
                    openMicBtn.background = roundedBackground(Color.parseColor("#059669"), 14f, Color.parseColor("#34D399"), 2)
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🗣️"
                    floatingMicBtn.background = roundedBackground(Color.parseColor("#CC059669"), 18f, Color.parseColor("#34D399"), 2)
                }
            } else {
                if (::micBtn.isInitialized) {
                    micBtn.text = "🟢 Open Mic"
                    micBtn.background = roundedBackground(Color.parseColor("#0F766E"), 14f, Color.parseColor("#14B8A6"), 2)
                }
                if (::openMicBtn.isInitialized) {
                    openMicBtn.text = "🟢 Mute"
                    openMicBtn.background = roundedBackground(Color.parseColor("#0F766E"), 14f, Color.parseColor("#14B8A6"), 2)
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🟢"
                    floatingMicBtn.background = roundedBackground(Color.parseColor("#CC0F766E"), 18f, Color.parseColor("#14B8A6"), 2)
                }
            }
        }
    }

    private fun handleMicTouchEvent(view: View, event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        view.parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // If mic is locked in Smart Open-Mic mode, tapping it mutes & unlocks!
                if (isVoiceLocked) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    isVoiceLocked = false
                    stopLiveVoiceStreaming()
                    resetMicButtonStates()
                    note("🎙️ Mic muted")
                    return true
                }

                micTouchStartY = event.rawY
                isTrackingSwipeUp = true
                view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                if (::micBtn.isInitialized) {
                    micBtn.text = "🔴 Talking…"
                    micBtn.background = roundedBackground(Color.parseColor("#DC2626"), 14f)
                }
                if (::floatingMicBtn.isInitialized) {
                    floatingMicBtn.text = "🗣️"
                    floatingMicBtn.background = roundedBackground(Color.parseColor("#DC2626"), 18f)
                }
                // Start instant live PCM streaming immediately!
                startLiveVoiceStreaming()
                showHud("🎙️ Hold to talk · Swipe up to lock")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTrackingSwipeUp && !isVoiceLocked) {
                    val deltaY = micTouchStartY - event.rawY // Dragging upwards
                    val lockThreshold = 18f * density // 18dp swipe-up threshold (easy and smooth to trigger!)
                    if (deltaY >= lockThreshold) {
                        isVoiceLocked = true
                        isTrackingSwipeUp = false
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
                                    android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                                )
                            }
                        } catch (_: Exception) {}

                        // Subtle bounce animation to confirm lock
                        view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
                            view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()
                        }.start()

                        updateMicButtonsState(speaking = true)
                        note("🟢 Open Mic active! Speak freely. Tap to mute.")
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isVoiceLocked) {
                    // Smart Open-Mic mode is active! Keep streaming hands-free
                    return true
                }
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetMicButtonStates()
                stopLiveVoiceStreaming()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isVoiceLocked) {
                    return true
                }
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                resetMicButtonStates()
                stopLiveVoiceStreaming()
                return true
            }
        }
        return false
    }

    private fun startLiveVoiceStreaming() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (isLiveStreaming) return
        isLiveStreaming = true

        liveRecordThread = Thread({
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val chunkSize = 1600 // 100ms at 16kHz 16-bit (3200 bytes)
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
                    ui.post { note("Cannot start microphone") }
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

                // Duck movie volume while speaking into mic
                ui.post { player?.volume = 0.15f }

                while (isLiveStreaming && !destroyed) {
                    val readShorts = record.read(shortBuffer, 0, chunkSize)
                    if (readShorts <= 0) continue

                    // Calculate RMS amplitude for Voice Activity Detection
                    var sum = 0.0
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i].toLong()
                        sum += (s * s)
                    }
                    val rms = kotlin.math.sqrt(sum / readShorts)
                    val now = System.currentTimeMillis()

                    // PTT mode (holding button): always transmits.
                    // Open-Mic mode (hands-free): checks calibrated threshold (rms >= 260.0).
                    val isSpeech = !isVoiceLocked || (rms >= 260.0)

                    if (isSpeech) {
                        lastSpeechTime = now
                        if (!localSpeaking) {
                            localSpeaking = true
                            isLocalSpeaking = true
                            ui.post {
                                if (destroyed) return@post
                                player?.volume = 0.15f
                                showHud("🗣️ You're speaking…")
                                updateMicButtonsState(speaking = true)
                            }
                        }
                    }

                    if (localSpeaking) {
                        if (isVoiceLocked && !isSpeech && (now - lastSpeechTime > 1200L)) {
                            // In Open-Mic mode: silence lasting > 1.2s -> restore movie volume
                            localSpeaking = false
                            isLocalSpeaking = false
                            ui.post {
                                if (destroyed) return@post
                                player?.volume = 1.0f
                                hideHudDelayed()
                                updateMicButtonsState(speaking = false)
                            }
                            worker.execute {
                                send(JSONObject().put("type", "voice_chunk").put("audio", "").put("speaking", false))
                            }
                        } else {
                            // Stream speech PCM chunk
                            for (i in 0 until readShorts) {
                                val v = shortBuffer[i].toInt()
                                byteBuffer[i * 2] = (v and 0xFF).toByte()
                                byteBuffer[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                            }
                            val b64 = android.util.Base64.encodeToString(
                                byteBuffer, 0, readShorts * 2, android.util.Base64.NO_WRAP
                            )
                            worker.execute {
                                send(JSONObject().put("type", "voice_chunk").put("audio", b64).put("speaking", true))
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
                ui.post { note("Live mic error: ${e.message}") }
            }
        }, "LiveVoiceRecordThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopLiveVoiceStreaming() {
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
        ui.post {
            player?.volume = 1.0f
            hideHudDelayed()
        }
        worker.execute {
            send(JSONObject().put("type", "voice_chunk").put("audio", "").put("speaking", false))
        }
    }

    @Synchronized
    private fun getOrCreateLiveAudioTrack(): AudioTrack {
        var track = liveAudioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            val sampleRate = 16000
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, 6400)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA) // Ensures loud output through LOUDSPEAKER!
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
            try {
                liveAudioEnhancer?.release()
                liveAudioEnhancer = LoudnessEnhancer(track.audioSessionId).apply {
                    setTargetGain(1400) // +14 dB clarity boost
                    enabled = true
                }
            } catch (_: Exception) {}
            track.play()
            liveAudioTrack = track
        }
        return track
    }

    private fun stopLiveAudioTrack() {
        synchronized(this) {
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
        }
    }

    private fun handleIncomingVoiceChunk(base64: String, isSpeaking: Boolean) {
        if (destroyed) return
        if (isSpeaking && base64.isNotEmpty()) {
            audioWorker.execute {
                try {
                    val pcmBytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    getOrCreateLiveAudioTrack().write(pcmBytes, 0, pcmBytes.size)
                } catch (_: Exception) {}
            }
            ui.post {
                if (destroyed) return@post
                player?.volume = 0.15f
                showHud("🗣️ Friend speaking…")
                ui.removeCallbacks(incomingSilenceRunnable)
                ui.postDelayed(incomingSilenceRunnable, 1500L)
            }
        } else if (!isSpeaking) {
            ui.post {
                if (destroyed) return@post
                ui.removeCallbacks(incomingSilenceRunnable)
                player?.volume = 1.0f
                hideHudDelayed()
            }
        }
    }

    private fun startVoiceRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        try {
            // Duck movie volume while user is speaking into the mic to avoid feedback
            player?.volume = 0.08f
            val file = File.createTempFile("voice_out_", ".m4a", cacheDir)
            recordFile = file
            recordStartTime = System.currentTimeMillis()
            val r = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                } catch (_: Exception) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            isRecording = true
            showHud("🎙️ Recording… Release to send")
        } catch (e: Exception) {
            isRecording = false
            player?.volume = 1.0f
            recordFile?.delete()
            recordFile = null
            note("Microphone error: ${e.message}")
        }
    }

    private fun stopVoiceRecording(sendAudio: Boolean) {
        if (!isRecording) return
        isRecording = false
        isVoiceLocked = false
        isTrackingSwipeUp = false
        resetMicButtonStates()
        // Restore movie volume immediately after speaking
        player?.volume = 1.0f
        hideHudDelayed()
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
            if (sendAudio && duration < 350) note("Hold to talk (too short)")
            return
        }

        worker.execute {
            try {
                val bytes = file.readBytes()
                file.delete()
                if (bytes.size in 1..300_000) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    send(JSONObject().put("type", "voice").put("audio", base64))
                    ui.post { note("🎙️ Voice note sent!") }
                }
            } catch (_: Exception) {
                file.delete()
            }
        }
    }

    private fun playIncomingVoice(base64: String) {
        worker.execute {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                val file = File.createTempFile("voice_in_", ".m4a", cacheDir)
                file.writeBytes(bytes)
                ui.post {
                    if (destroyed) {
                        file.delete()
                        return@post
                    }
                    showHud("🎙️ Friend speaking…")
                    // Smooth Audio Ducking: Reduce movie volume to 8% while friend speaks
                    player?.volume = 0.08f
                    try {
                        activeVoicePlayer?.release()
                        val mp = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                            )
                            prepare()
                            // Loudness enhancement: Boost friend's voice volume (+10 dB)
                            val enhancer = try {
                                LoudnessEnhancer(audioSessionId).apply {
                                    setTargetGain(1000)
                                    enabled = true
                                }
                            } catch (_: Exception) { null }

                            setVolume(1.0f, 1.0f)
                            setOnCompletionListener {
                                try { enhancer?.release() } catch (_: Exception) {}
                                it.release()
                                if (activeVoicePlayer === it) activeVoicePlayer = null
                                file.delete()
                                player?.volume = 1.0f // Restore normal movie sound!
                                hideHudDelayed()
                            }
                            setOnErrorListener { it, _, _ ->
                                try { enhancer?.release() } catch (_: Exception) {}
                                it.release()
                                if (activeVoicePlayer === it) activeVoicePlayer = null
                                file.delete()
                                player?.volume = 1.0f // Restore normal movie sound!
                                hideHudDelayed()
                                true
                            }
                            start()
                        }
                        activeVoicePlayer = mp
                    } catch (e: Exception) {
                        player?.volume = 1.0f
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                note("Microphone ready! Hold 🎙️ to talk.")
            } else {
                note("Microphone permission needed to use Push-to-Talk.")
            }
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        if (destroyed || !::controlsGroup.isInitialized) return
        controlsVisible = visible
        ui.removeCallbacks(hideControlsRunnable)
        if (visible) {
            sessionHeader.visibility = View.VISIBLE
            controlsGroup.visibility = View.VISIBLE
            sessionHeader.animate().alpha(1f).setDuration(200).start()
            controlsGroup.animate().alpha(1f).setDuration(200).start()
            if (player?.playWhenReady == true) {
                ui.postDelayed(hideControlsRunnable, 3000L)
            }
        } else {
            sessionHeader.animate().alpha(0f).setDuration(200).withEndAction {
                if (!controlsVisible && ::sessionHeader.isInitialized) sessionHeader.visibility = View.GONE
            }.start()
            controlsGroup.animate().alpha(0f).setDuration(200).withEndAction {
                if (!controlsVisible && ::controlsGroup.isInitialized) controlsGroup.visibility = View.GONE
            }.start()
        }
    }

    private fun toggleControlsVisibility() {
        setControlsVisible(!controlsVisible)
    }

    private fun resetControlsTimer() {
        ui.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && player?.playWhenReady == true) {
            ui.postDelayed(hideControlsRunnable, 3000L)
        }
    }

    private fun seekRelative(deltaMs: Long) {
        player?.let { p ->
            val duration = p.duration
            val maxPos = if (duration > 0) duration else Long.MAX_VALUE
            val newPos = (p.currentPosition + deltaMs).coerceIn(0L, maxPos)
            control(newPos, p.playWhenReady)
            resetControlsTimer()
        }
    }

    private fun showRipple(isForward: Boolean) {
        ui.post {
            if (destroyed || !::rippleContainer.isInitialized) return@post
            val target = if (isForward) rightRipple else leftRipple
            target.visibility = View.VISIBLE
            target.alpha = 1f
            target.scaleX = 0.8f
            target.scaleY = 0.8f
            target.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(0f)
                .setDuration(600)
                .withEndAction {
                    target.visibility = View.GONE
                }
                .start()
        }
    }

    private fun showHud(text: String) {
        if (destroyed || !::hudContainer.isInitialized) return
        ui.removeCallbacks(hideHudRunnable)
        hudText.text = text
        hudContainer.visibility = View.VISIBLE
        hudContainer.alpha = 1f
    }

    private fun hideHudDelayed() {
        ui.removeCallbacks(hideHudRunnable)
        ui.postDelayed(hideHudRunnable, 800)
    }

    private fun spawnFloatingEmoji(emoji: String) {
        ui.post {
            if (destroyed || !::emojiContainer.isInitialized) return@post
            val density = resources.displayMetrics.density
            val tv = TextView(this).apply {
                text = emoji
                textSize = 38f
                alpha = 0f
                setShadowLayer(10f * density, 0f, 3f * density, Color.parseColor("#B3000000"))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                }
            }
            emojiContainer.addView(tv)

            val containerW = emojiContainer.width.takeIf { it > 0 } ?: (300 * density).toInt()
            val containerH = emojiContainer.height.takeIf { it > 0 } ?: (200 * density).toInt()

            val startX = (containerW * (0.20f + (Math.random().toFloat() * 0.65f))).coerceIn(15f * density, (containerW - 55 * density).toFloat())
            val startY = containerH * 0.82f
            tv.x = startX
            tv.y = startY

            val driftX = (Math.random().toFloat() - 0.5f) * 80f * density
            val targetY = startY - (containerH * 0.72f)

            tv.scaleX = 0.4f
            tv.scaleY = 0.4f

            // 1. Pop in clearly (350ms)
            tv.animate()
                .alpha(1f)
                .scaleX(1.35f)
                .scaleY(1.35f)
                .setDuration(350)
                .withEndAction {
                    // 2. Slow, graceful drift upwards staying fully visible (2600ms)
                    tv.animate()
                        .x(startX + driftX)
                        .y(targetY)
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .setDuration(2600)
                        .withEndAction {
                            // 3. Gentle fade out at the very top (900ms)
                            tv.animate()
                                .alpha(0f)
                                .y(targetY - (25f * density))
                                .scaleX(0.85f)
                                .scaleY(0.85f)
                                .setDuration(900)
                                .withEndAction {
                                    emojiContainer.removeView(tv)
                                }
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun roundedBackground(bgColor: Int, radiusDp: Float, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radiusDp * density
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke((strokeWidthDp * density).toInt(), strokeColor)
            }
        }
    }

    private fun styledButton(label: String, bgColor: Int, textColor: Int = Color.WHITE, action: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = roundedBackground(bgColor, 10f)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            setOnClickListener { action() }
        }
    }

    private fun stepCard(stepNumber: String, stepTitle: String, description: String): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = roundedBackground(Color.parseColor("#1E293B"), 14f, Color.parseColor("#334155"), 1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (5 * density).toInt(), 0, (5 * density).toInt())
            }
            val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val badge = TextView(context).apply {
                text = stepNumber
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundedBackground(Color.parseColor("#3B82F6"), 12f)
                layoutParams = LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
            }
            val titleView = TextView(context).apply {
                text = stepTitle
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            header.addView(badge)
            header.addView(titleView)
            addView(header)

            val descView = TextView(context).apply {
                text = description
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding((32 * density).toInt(), (2 * density).toInt(), 0, (8 * density).toInt())
            }
            addView(descView)
        }
    }

    private fun spacer(heightDp: Int): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, (heightDp * density).toInt())
        }
    }

    private fun spacerHoriz(widthDp: Int): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams((widthDp * density).toInt(), 1)
        }
    }

    private fun selectTab(isHost: Boolean) {
        if (isHost) {
            hostTab.background = roundedBackground(Color.parseColor("#2563EB"), 10f)
            hostTab.setTextColor(Color.WHITE)
            joinTab.background = roundedBackground(Color.TRANSPARENT, 10f)
            joinTab.setTextColor(Color.parseColor("#94A3B8"))
            hostContainer.visibility = View.VISIBLE
            joinContainer.visibility = View.GONE
            note("Host mode · Tap 'Process & Create Room' to begin")
        } else {
            joinTab.background = roundedBackground(Color.parseColor("#2563EB"), 10f)
            joinTab.setTextColor(Color.WHITE)
            hostTab.background = roundedBackground(Color.TRANSPARENT, 10f)
            hostTab.setTextColor(Color.parseColor("#94A3B8"))
            hostContainer.visibility = View.GONE
            joinContainer.visibility = View.VISIBLE
            note("Join mode · Step 1: Paste friend's invite to begin")
        }
    }

    private fun updateScreenMode() {
        ui.post {
            if (destroyed) return@post
            val density = resources.displayMetrics.density
            if (connected) {
                setupScrollView.visibility = View.GONE
                playerContainer.visibility = View.VISIBLE
                sessionHeader.visibility = View.VISIBLE
                controlsGroup.visibility = View.VISIBLE
                setControlsVisible(true)
                roomCodeBadge.text = "Room #${if (code.length > 8) code.take(6) + "…" else code}"
                mainLayout.setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            } else {
                setupScrollView.visibility = View.VISIBLE
                playerContainer.visibility = View.GONE
                sessionHeader.visibility = View.GONE
                controlsGroup.visibility = View.GONE
                mainLayout.setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            }
        }
    }

    private fun note(text: String) {
        ui.post {
            if (!destroyed) {
                status.text = text
                if (::sessionStatus.isInitialized) sessionStatus.text = text
            }
        }
    }
    private fun endpoint(): String {
        val input = server.text.toString().trim()
        val prefServer = getPreferences(0).getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        val fallbackServer = if (prefServer.contains("trycloudflare.com")) DEFAULT_SERVER else prefServer
        val raw = if (input.isNotEmpty() && !input.contains("trycloudflare.com")) input else fallbackServer
        val url = raw.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "Enter only the trusted HTTPS server origin, without a path or credentials"
        }
        val value = url.toString().trimEnd('/')
        getPreferences(0).edit().putString("server", value).apply()
        return value
    }

    private fun pickMovie(autoCreate: Boolean = false) {
        if (!connected && !connecting) {
            autoCreateAfterPick = autoCreate
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 10)
        }
    }

    private fun processCreateRoom() {
        if (connected || connecting) return
        if (movie == null) {
            note("Select a movie to launch room…")
            pickMovie(autoCreate = true)
        } else {
            createRoom()
        }
    }

    @Deprecated("Android activity result API retained to avoid an extra UI framework dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 10 || resultCode != RESULT_OK) return
        try {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            var name = "Movie"
            var length = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0); if (!c.isNull(1)) length = c.getLong(1) }
            }
            var detectedMime: String? = null
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Cannot open movie")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                stream.channel.position(0)
                val chSize = stream.channel.size()
                if (chSize > 0) length = chSize

                val headerBuf = ByteBuffer.allocate(16)
                val readHeader = stream.channel.read(headerBuf, 0)
                if (readHeader >= 4) {
                    val b = headerBuf.array()
                    detectedMime = when {
                        b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/x-matroska"
                        readHeader >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> "video/mp4"
                        readHeader >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
                                b[8] == 'A'.code.toByte() && b[9] == 'V'.code.toByte() && b[10] == 'I'.code.toByte() -> "video/x-msvideo"
                        else -> null
                    }
                }
            }
            require(length > 0) { "Choose a seekable local video with a known size" }
            closeMovieChannel()
            val ext = name.substringAfterLast('.', "").lowercase()
            val extMime = when (ext) {
                "mkv" -> "video/x-matroska"
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "ts", "m2ts" -> "video/mp2t"
                else -> null
            }
            mime = detectedMime ?: extMime ?: contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            fileLabel.text = "✓ $name · ${size / 1024 / 1024} MB"
            fileLabel.setTextColor(Color.parseColor("#34D399"))
            if (autoCreateAfterPick) {
                autoCreateAfterPick = false
                note("Movie ready! Creating room & invite…")
                ui.postDelayed({ createRoom() }, 200)
            } else {
                note("Movie ready! Tap 'Process & Create Room' to launch.")
            }
        } catch (e: Exception) { note(e.message ?: "Cannot read movie") }
    }
    private fun createRoom() {
        try {
            require(movie != null) { "Choose a movie first" }
            var key = creatorKey.text.toString().trim()
            if (key.isEmpty()) {
                key = getPreferences(0).getString("creator_key", DEFAULT_CREATOR_KEY) ?: DEFAULT_CREATOR_KEY
                creatorKey.setText(key)
            }
            require(key.length >= 32) { "Enter your server's host access key" }
            getPreferences(0).edit().putString("creator_key", key).apply()
            base = endpoint(); connecting = true; host = true
            val attempt = ++generation
            val body = JSONObject().put("size", size).put("mime", mime).toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$base/rooms").header("Authorization", "Bearer $key").post(body).build()
            worker.execute {
                try {
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Server returned ${response.code}" }
                        val data = JSONObject(response.body!!.string())
                        ui.post { if (!destroyed && connecting && generation == attempt) {
                            code = data.getString("code"); token = data.getString("hostToken"); invite = data.getString("invite"); connect()
                            if (onlineFriends.isNotEmpty()) {
                                note("🟢 Room created! Tap '👥 Friends' to invite ${onlineFriends[0].optString("name")}, or use '📤 Invite'.")
                            } else {
                                try {
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$base|$invite")
                                    }, "Invite your friend"))
                                } catch (_: Exception) {}
                            }
                        } }
                    }
                } catch (e: Exception) { ui.post { if (generation == attempt) { connecting = false; note(e.message ?: "Connection failed") } } }
            }
            note("Creating private room…")
        } catch (e: Exception) { connecting = false; note(e.message ?: "Unable to create room") }
    }
    private fun joinRoom() {
        val rawInvite = inviteInput.text.toString().trim()
        doJoin(rawInvite)
    }
    private fun doJoin(rawInvite: String) {
        try {
            val input = rawInvite.split('|')
            require(input.size == 2) { "Paste the full invite: server|room.token" }
            getPreferences(0).edit().putString("last_invite", rawInvite).apply()
            server.setText(input[0].trim()); base = endpoint()
            val parts = input[1].trim().split('.')
            require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Invalid invite" }
            code = parts[0]; host = false; connecting = true
            val attempt = ++generation
            val request = Request.Builder().url("$base/rooms/$code/join")
                .header("Authorization", "Bearer ${parts[1]}")
                .post(ByteArray(0).toRequestBody(null)).build()
            worker.execute {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.code == 409) {
                            throw IllegalStateException("Host is still connecting. Please wait a moment and tap Join again.")
                        }
                        check(response.isSuccessful) { "Invite expired, used, or unavailable (${response.code}). Ask host for a new room." }
                        val json = JSONObject(response.body!!.string())
                        val guestToken = json.getString("guestToken")
                        val m = json.optString("mime", "")
                        val s = json.optLong("size", 0L)
                        ui.post { if (!destroyed && connecting && generation == attempt) {
                            token = guestToken
                            if (m.isNotEmpty()) remoteMime = m
                            if (s > 0L) remoteSize = s
                            inviteInput.text.clear()
                            connect()
                        } }
                    }
                } catch (e: Exception) { ui.post { if (generation == attempt) { connecting = false; note(e.message ?: "Join failed") } } }
            }
            note("Connecting to friend's movie…")
        } catch (e: Exception) { connecting = false; note(e.message ?: "Invalid invite") }
    }
    private fun connect() {
        latest = null; lastReady = null
        val request = Request.Builder().url(base.replaceFirst("https://", "wss://") + "/rooms/$code/ws").header("Authorization", "Bearer $token").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (!destroyed && socket === webSocket) {
                        reconnectAttempts = 0
                        ui.removeCallbacks(reconnectTask)
                        connected = true
                        connecting = false
                        setupPlayer()
                        updateScreenMode()
                        note(if (host) "🟢 Ready! Tap Play to preview or share invite." else "🟢 Joined! Buffering movie…")
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    if (data.optString("type") == "read" && host && socket === webSocket) {
                        val source = movie
                        val sourceSize = size
                        if (source != null) videoWorker.execute { readChunk(webSocket, data, source, sourceSize) }
                    } else ui.post {
                        if (socket === webSocket) when (data.optString("type")) {
                            "state" -> { latest = data; applyState(data) }
                            "reaction" -> {
                                val emoji = data.optString("emoji", "🍿")
                                spawnFloatingEmoji(emoji)
                            }
                            "voice" -> {
                                val audio = data.optString("audio")
                                if (audio.isNotEmpty()) playIncomingVoice(audio)
                            }
                            "voice_chunk" -> {
                                val audio = data.optString("audio")
                                val isSpeaking = data.optBoolean("speaking", false)
                                handleIncomingVoiceChunk(audio, isSpeaking)
                            }
                            "error" -> note(data.optString("message"))
                        }
                    }
                } catch (_: Exception) { note("Invalid server message") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ui.post {
                    if (socket === webSocket) {
                        val is404 = response?.code == 404 || t.message?.contains("404") == true
                        if (is404) {
                            val wasHost = host
                            leave()
                            if (wasHost) {
                                note("Room expired on server. Tap 'Process & Create Room' to start fresh.")
                            } else {
                                note("Room expired or closed by host. Ask host for a new invite link.")
                            }
                        } else if (token.isNotEmpty() && !destroyed) {
                            scheduleReconnect(t.message ?: "network error")
                        } else {
                            leave()
                            note("Connection lost: ${t.message}. Create or join a room again.")
                        }
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ui.post {
                    if (socket === webSocket) {
                        socket = null
                        if (code == 1000 && reason == "Left room") {
                            leave()
                        } else if (code == 1001 && reason.contains("closed", ignoreCase = true)) {
                            leave()
                            note("Host closed the room.")
                        } else if (token.isNotEmpty() && !destroyed) {
                            scheduleReconnect(if (reason.isNotEmpty()) reason else "closed $code")
                        } else {
                            leave()
                            note("Room closed. Create or join again.")
                        }
                    }
                }
            }
        })
    }
    private fun scheduleReconnect(errorMsg: String) {
        socket = null; connected = false
        if (reconnectAttempts >= 15) {
            reconnectAttempts = 0
            leave()
            note("Connection lost: $errorMsg. Create or join again.")
            return
        }
        reconnectAttempts++
        val delay = (1200L * reconnectAttempts).coerceIn(1500L, 4000L)
        note("Reconnecting in ${delay / 1000}s…")
        ui.removeCallbacks(reconnectTask)
        ui.postDelayed(reconnectTask, delay)
    }
    private fun setupPlayer() {
        if (player != null) {
            val ready = player?.playbackState == Player.STATE_READY
            send(JSONObject().put("type", "ready").put("ready", ready))
            return
        }
        val httpFactory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        val factory = DefaultDataSource.Factory(this, httpFactory)
        val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(factory, extractorsFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 3_000,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setBackBuffer(5_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        player = p; video.player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val ready = playbackState == Player.STATE_READY
                if (ready != lastReady) {
                    lastReady = ready
                    send(JSONObject().put("type", "ready").put("ready", ready))
                }
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        val pct = p.bufferedPercentage
                        note(if (pct > 0) "⏳ Buffering $pct%…" else "⏳ Buffering movie…")
                    }
                    Player.STATE_READY -> {
                        if (pendingInitialSeek > 1500L) {
                            val target = pendingInitialSeek
                            pendingInitialSeek = 0L
                            p.seekTo(target)
                        }
                        val isPlaying = player?.playWhenReady == true
                        updatePlayPauseButtons(isPlaying)
                        note(if (isPlaying) "▶ Playing in sync" else "⏸ Paused (Both in sync)")
                        if (isPlaying) resetControlsTimer() else setControlsVisible(true)
                    }
                    Player.STATE_ENDED -> control(p.currentPosition, false)
                    Player.STATE_IDLE -> note("Player idle")
                }
            }
            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (p.playbackState == Player.STATE_BUFFERING) {
                    val pct = p.bufferedPercentage
                    note(if (pct > 0) "⏳ Buffering $pct%…" else "⏳ Buffering movie…")
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                send(JSONObject().put("type", "ready").put("ready", false))
                note("Video error: ${error.errorCodeName}. Details: ${error.message}")
            }
        })
        val ext = when {
            remoteMime.contains("matroska", ignoreCase = true) || remoteMime.endsWith("mkv", ignoreCase = true) -> ".mkv"
            remoteMime.contains("mp4", ignoreCase = true) -> ".mp4"
            remoteMime.contains("webm", ignoreCase = true) -> ".webm"
            else -> ""
        }
        val mediaUri = if (host) movie!! else Uri.parse("$base/rooms/$code/movie$ext?token=$token")
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .apply {
                if (!host && remoteMime.isNotEmpty()) {
                    setMimeType(remoteMime)
                }
            }
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        latest?.let { applyState(it) }
        ui.removeCallbacks(progressUpdater)
        ui.post(progressUpdater)
    }

    private fun updatePlayPauseButtons(isPlaying: Boolean) {
        ui.post {
            if (!destroyed && ::bPlay.isInitialized && ::bPause.isInitialized) {
                if (isPlaying) {
                    bPlay.background = roundedBackground(Color.parseColor("#10B981"), 10f)
                    bPlay.text = "▶ Playing"
                    bPause.background = roundedBackground(Color.parseColor("#334155"), 10f)
                    bPause.text = "⏸ Pause"
                } else {
                    bPlay.background = roundedBackground(Color.parseColor("#2563EB"), 10f)
                    bPlay.text = "▶ Play"
                    bPause.background = roundedBackground(Color.parseColor("#DC2626"), 10f)
                    bPause.text = "⏸ Paused"
                }
            }
        }
    }

    private fun applyState(data: JSONObject) {
        val p = player ?: return
        val position = data.getLong("position")
        val playing = data.getBoolean("playing")
        val guestConnected = data.optBoolean("guestConnected", false)
        val guestReady = data.optBoolean("guestReady", false)

        val m = data.optString("mime")
        if (m.isNotEmpty()) remoteMime = m
        val s = data.optLong("size", 0L)
        if (s > 0L) remoteSize = s

        p.playWhenReady = playing
        updatePlayPauseButtons(playing)

        if (host) {
            if (!guestConnected) {
                note("⏳ Solo mode · Waiting for friend to join (${code})…")
            } else if (!guestReady) {
                note("⏳ Friend joined! Buffering movie…")
            } else if (playing) {
                note("▶ Watching together in sync")
            } else {
                note("⏸ Paused (Both in sync)")
            }
        } else {
            val hostConnected = data.optBoolean("hostConnected", true)
            if (!hostConnected) {
                note("⏳ Host temporarily reconnecting… playback paused")
            } else if (p.playbackState == Player.STATE_BUFFERING) {
                val pct = p.bufferedPercentage
                note(if (pct > 0) "⏳ Buffering $pct%…" else "⏳ Buffering movie…")
            } else if (playing) {
                note("▶ Watching together in sync")
            } else {
                note("⏸ Paused by host/friend")
            }
        }

        if (playing) {
            resetControlsTimer()
            if (p.playbackState == Player.STATE_READY) {
                val drift = position - p.currentPosition
                when {
                    abs(drift) > 3500 -> {
                        p.seekTo(position)
                        p.playbackParameters = PlaybackParameters(1.0f)
                    }
                    drift > 200 -> p.playbackParameters = PlaybackParameters(1.04f)
                    drift < -200 -> p.playbackParameters = PlaybackParameters(0.96f)
                    else -> p.playbackParameters = PlaybackParameters(1.0f)
                }
            } else {
                pendingInitialSeek = position
            }
        } else {
            setControlsVisible(true)
            p.playbackParameters = PlaybackParameters(1.0f)
            if (p.playbackState == Player.STATE_READY && abs(p.currentPosition - position) > 3000) {
                p.seekTo(position)
            } else if (p.playbackState != Player.STATE_READY) {
                pendingInitialSeek = position
            }
        }
    }
    private fun control(position: Long, playing: Boolean) {
        if (connected && player != null) {
            send(JSONObject().put("type", "control").put("position", position).put("playing", playing))
            resetControlsTimer()
        }
    }
    private fun send(data: JSONObject) { socket?.send(data.toString()) }
    private fun closeMovieChannel() {
        try { movieChannel?.close() } catch (_: Exception) {}
        try { moviePfd?.close() } catch (_: Exception) {}
        movieChannel = null
        moviePfd = null
    }
    private fun getOrOpenMovieChannel(uri: Uri): FileChannel {
        return movieChannel ?: synchronized(this) {
            movieChannel ?: run {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Cannot open file")
                moviePfd = pfd
                FileInputStream(pfd.fileDescriptor).channel.also { movieChannel = it }
            }
        }
    }
    private fun readChunk(ws: WebSocket, request: JSONObject, source: Uri, sourceSize: Long) {
        if (socket !== ws) return
        val id = request.optString("id")
        try {
            val offset = request.getLong("offset")
            val length = request.getInt("length")
            require(offset >= 0 && length in 1..262144)
            val channel = getOrOpenMovieChannel(source)
            val chSize = try { channel.size() } catch (_: Exception) { sourceSize }
            if (chSize > 0 && offset >= chSize) {
                ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
                return
            }
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(idBytes.size + length)
            System.arraycopy(idBytes, 0, payload, 0, idBytes.size)
            val buffer = ByteBuffer.wrap(payload, idBytes.size, length)
            var read = 0
            while (read < length) {
                val n = channel.read(buffer, offset + read)
                if (n <= 0) break
                read += n
            }
            val sent = ws.send(payload.toByteString(0, payload.size))
            if (!sent) {
                ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
            }
        } catch (_: Exception) {
            ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
        }
    }

    private fun getUserName(): String {
        return getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
    }

    private fun setUserName(name: String) {
        getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
            .edit().putString("user_name", name.trim()).apply()
    }

    private fun getUserId(): String {
        val prefs = getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("user_id", "") ?: ""
        if (uid.isEmpty()) {
            uid = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("user_id", uid).apply()
        }
        return uid
    }

    private fun showEditNameDialog(initial: Boolean = false) {
        if (destroyed) return
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "Your name (e.g. Parasuram)"
            setText(getUserName())
            setSingleLine(true)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = roundedBackground(Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(if (initial) "👋 Welcome! Enter Your Name" else "✏️ Change Display Name")
            .setMessage(if (initial) "Enter your name so friends can see when you're online and invite you directly to watch movies together." else "Your friends will see this name when you are online.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    setUserName(name)
                    myUserName = name
                    if (::userBadge.isInitialized) userBadge.text = "👤 $name ✏️"
                    note("Hello, $name! 👋")
                    connectLobby()
                }
            }
            .setCancelable(!initial)
            .show()
    }

    @Volatile private var isLobbyConnected = false

    private fun connectLobby() {
        if (destroyed) return
        val name = getUserName()
        if (name.isEmpty()) return
        val uid = getUserId()
        val lobbyBase = endpoint()
        val url = lobbyBase.replaceFirst("https://", "wss://") + "/lobby/ws"
        val req = Request.Builder().url(url).build()

        lobbySocket?.close(1000, "Reconnecting")
        lobbySocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isLobbyConnected = true
                ui.post {
                    if (destroyed) return@post
                    val hello = JSONObject().put("type", "hello").put("userId", uid).put("name", name)
                    webSocket.send(hello.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    when (data.optString("type")) {
                        "lobby_peers" -> {
                            val peersArray = data.optJSONArray("peers") ?: JSONArray()
                            val list = mutableListOf<JSONObject>()
                            for (i in 0 until peersArray.length()) {
                                val peer = peersArray.getJSONObject(i)
                                if (peer.optString("id") != uid) {
                                    list.add(peer)
                                }
                            }
                            ui.post {
                                if (!destroyed) {
                                    onlineFriends = list
                                    updateOnlineFriendsUI()
                                }
                            }
                        }
                        "incoming_invite" -> {
                            val fromName = data.optString("fromName", "A friend")
                            val fromUid = data.optString("fromUserId")
                            val inviteCode = data.optString("code")
                            val invitePayload = data.optString("invite")
                            ui.post {
                                if (!destroyed) {
                                    showIncomingInviteDialog(fromName, fromUid, inviteCode, invitePayload)
                                }
                            }
                        }
                        "invite_response" -> {
                            val accepted = data.optBoolean("accepted")
                            ui.post {
                                if (!destroyed) {
                                    if (accepted) {
                                        note("🎉 Friend accepted! Connecting to movie…")
                                    } else {
                                        note("Friend declined or is busy right now.")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isLobbyConnected = false
                ui.post {
                    if (!destroyed) {
                        ui.removeCallbacks(lobbyReconnectTask)
                        ui.postDelayed(lobbyReconnectTask, 5000L)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isLobbyConnected = false
                ui.post {
                    if (!destroyed && code != 1000) {
                        ui.removeCallbacks(lobbyReconnectTask)
                        ui.postDelayed(lobbyReconnectTask, 5000L)
                    }
                }
            }
        })
    }

    private fun updateOnlineFriendsUI() {
        if (!::onlineFriendsContainer.isInitialized) return
        val density = resources.displayMetrics.density
        onlineFriendsContainer.removeAllViews()
        if (::friendsHeaderBtn.isInitialized) {
            friendsHeaderBtn.text = "👥 Friends (${onlineFriends.size})"
        }

        if (onlineFriends.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "No friends online right now · Tell a friend to open the app!"
                textSize = 11f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            onlineFriendsContainer.addView(emptyNotice)
            return
        }

        for (friend in onlineFriends) {
            val fName = friend.optString("name", "Friend")
            val fUid = friend.optString("id")
            val friendChip = TextView(this).apply {
                text = "🟢 $fName  ➔ Invite"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedBackground(Color.parseColor("#059669"), 14f)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    if (connected && host && code.isNotEmpty() && invite.isNotEmpty()) {
                        sendPeerInvite(fUid, fName)
                        text = "⏳ Invited $fName…"
                        background = roundedBackground(Color.parseColor("#D97706"), 14f)
                    } else {
                        note("Tap 'Process & Create Room' to launch movie, then invite $fName!")
                        showFriendsInviteDialog()
                    }
                }
            }
            onlineFriendsContainer.addView(friendChip)
        }
    }

    private fun showIncomingInviteDialog(fromName: String, fromUid: String, inviteCode: String, invitePayload: String) {
        if (destroyed) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                (getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1)
                )
            }
        } catch (_: Exception) {}

        AlertDialog.Builder(this)
            .setTitle("🎬 Movie Invitation!")
            .setMessage("$fromName invited you to watch a movie together right now!")
            .setPositiveButton("Accept & Watch 🍿") { _, _ ->
                lobbySocket?.send(JSONObject().put("type", "invite_response").put("toUserId", fromUid).put("accepted", true).toString())
                if (connected) leave()
                val fullInvite = "$base|$invitePayload"
                inviteInput.setText(fullInvite)
                selectTab(false)
                doJoin(fullInvite)
            }
            .setNegativeButton("Decline") { _, _ ->
                lobbySocket?.send(JSONObject().put("type", "invite_response").put("toUserId", fromUid).put("accepted", false).toString())
            }
            .setCancelable(true)
            .show()
    }

    private fun showFriendsInviteDialog() {
        if (destroyed) return
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        if (onlineFriends.isEmpty()) {
            val emptyMsg = TextView(this).apply {
                text = "None of your friends are online in the app right now.\n\nYou can still share the room link via WhatsApp, SMS, or other apps using the button below!"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            container.addView(emptyMsg)
        } else {
            val tip = TextView(this).apply {
                text = "Tap a friend to send them a 1-click in-app watch invitation:"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            container.addView(tip)
            for (friend in onlineFriends) {
                val fName = friend.optString("name", "Friend")
                val fUid = friend.optString("id")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                }
                val friendLabel = TextView(this).apply {
                    text = "🟢 $fName (Online)"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val inviteBtn = styledButton("🍿 Invite", Color.parseColor("#059669")) {
                    if (connected && host && code.isNotEmpty() && invite.isNotEmpty()) {
                        sendPeerInvite(fUid, fName)
                        note("📨 Sent in-app invite to $fName!")
                    } else {
                        note("Create room first to invite $fName")
                    }
                }.apply {
                    textSize = 11f
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                }
                row.addView(friendLabel)
                row.addView(inviteBtn)
                container.addView(row)
            }
        }

        val shareFallbackBtn = styledButton("📤 Share Link via Other Apps", Color.parseColor("#6366F1")) {
            if (invite.isNotEmpty()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$base|$invite")
                }, "Invite your friend"))
            } else {
                note("Launch a room first to get an invite link.")
            }
        }.apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (10 * density).toInt()
            }
        }
        container.addView(shareFallbackBtn)

        AlertDialog.Builder(this)
            .setTitle("👥 Online Friends (${onlineFriends.size})")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendPeerInvite(targetUid: String, targetName: String) {
        if (code.isEmpty() || invite.isEmpty()) {
            note("Launch a room first to invite $targetName")
            return
        }
        val myName = getUserName()
        val msg = JSONObject()
            .put("type", "invite_peer")
            .put("toUserId", targetUid)
            .put("fromName", myName)
            .put("code", code)
            .put("invite", invite)
        lobbySocket?.send(msg.toString())
        note("📨 Invited $targetName! Waiting for them to accept…")
    }

    private fun leave() {
        val roomToRevoke = code
        val tokenToRevoke = token
        val wasHost = host
        if (wasHost && roomToRevoke.isNotEmpty() && tokenToRevoke.isNotEmpty()) {
            worker.execute {
                try {
                    val req = Request.Builder()
                        .url("$base/rooms/$roomToRevoke")
                        .delete()
                        .header("Authorization", "Bearer $tokenToRevoke")
                        .build()
                    client.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
        }
        generation++
        reconnectAttempts = 0
        ui.removeCallbacks(reconnectTask)
        ui.removeCallbacks(progressUpdater)
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(hideHudRunnable)
        ui.removeCallbacks(singleTapRunnable)
        ui.removeCallbacks(incomingSilenceRunnable)
        if (isVoiceLocked) stopLiveVoiceStreaming()
        stopLiveAudioTrack()
        if (isRecording) stopVoiceRecording(sendAudio = false)
        resetMicButtonStates()
        try { activeVoicePlayer?.release() } catch (_: Exception) {}
        activeVoicePlayer = null
        player?.volume = 1.0f
        closeMovieChannel()
        val old = socket; socket = null; old?.close(1000, "Left room")
        token = ""; code = ""; inviteInput.text.clear()
        player?.release(); player = null; video.player = null
        connected = false; connecting = false; invite = ""; latest = null
        if (::emojiContainer.isInitialized) emojiContainer.removeAllViews()
        updateScreenMode()
        note("Left room · Ready for next movie")
    }
    override fun onDestroy() {
        destroyed = true; ui.removeCallbacks(tick); ui.removeCallbacks(reconnectTask); ui.removeCallbacks(progressUpdater); ui.removeCallbacks(hideControlsRunnable); ui.removeCallbacks(hideHudRunnable); ui.removeCallbacks(singleTapRunnable); ui.removeCallbacks(incomingSilenceRunnable); ui.removeCallbacks(lobbyReconnectTask); lobbySocket?.close(1000, "Destroyed"); lobbySocket = null; leave(); closeMovieChannel(); worker.shutdownNow(); videoWorker.shutdownNow(); audioWorker.shutdownNow(); client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
