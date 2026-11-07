package com.watchtogether.app

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Vibrator
import android.os.VibrationEffect
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.media3.ui.PlayerView
import com.watchtogether.app.audio.VoiceManager
import com.watchtogether.app.chat.ChatController
import com.watchtogether.app.lobby.LobbyManager
import com.watchtogether.app.network.RelayClient
import com.watchtogether.app.player.SyncPlayerManager
import com.watchtogether.app.stream.MovieStreamChannel
import com.watchtogether.app.ui.FloatingEmojiManager
import com.watchtogether.app.ui.PlayerGestureController
import com.watchtogether.app.ui.UIUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    companion object {
        const val DEFAULT_SERVER = "https://watchtogether-relay.onrender.com"
        const val DEFAULT_CREATOR_KEY = "lbj1t0uyEHOgRhuDXI4r2OjLvNM9fpZ8vqNHzDpsmeo"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val videoWorker = Executors.newFixedThreadPool(8)

    // Modular Subsystem Managers
    private lateinit var relayClient: RelayClient
    private lateinit var playerManager: SyncPlayerManager
    private lateinit var voiceManager: VoiceManager
    private lateinit var lobbyManager: LobbyManager
    private lateinit var chatController: ChatController
    private lateinit var gestureController: PlayerGestureController
    private lateinit var emojiManager: FloatingEmojiManager
    private lateinit var movieChannel: MovieStreamChannel

    // UI Structure
    private lateinit var rootLayout: FrameLayout
    private lateinit var mainLayout: LinearLayout
    private lateinit var setupScrollView: ScrollView
    private lateinit var sessionHeader: LinearLayout
    private lateinit var roomCodeBadge: TextView
    private lateinit var sessionStatus: TextView
    private lateinit var hostContainer: LinearLayout
    private lateinit var joinContainer: LinearLayout
    private lateinit var hostTab: Button
    private lateinit var joinTab: Button
    private lateinit var fileLabel: TextView
    private lateinit var server: EditText
    private lateinit var creatorKey: EditText
    private lateinit var inviteInput: EditText
    private lateinit var status: TextView
    private lateinit var guestMovieLabel: TextView
    private lateinit var clearGuestMovieBtn: Button
    private lateinit var clipboardAutoJoinCard: LinearLayout
    private lateinit var friendsHeaderBtn: Button
    private lateinit var floatingMicBtn: TextView
    private lateinit var dataSaverBadge: TextView
    private lateinit var reconnectBanner: TextView

    // Video Player & Controls Views
    private lateinit var playerContainer: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var emojiContainer: FrameLayout
    private lateinit var controlsGroup: LinearLayout
    private lateinit var reactionBar: LinearLayout
    private lateinit var seekbarRow: LinearLayout
    private lateinit var bottomControls: LinearLayout

    // Local Movie State
    private var movie: Uri? = null
    private var guestMovie: Uri? = null
    private var size: Long = 0L
    private var mime: String = "video/mp4"
    private var autoCreateAfterPick = false
    private var destroyed = false
    private var controlsVisible = true

    // DSA: Token Bucket Rate Limiter for reaction animations and network optimization
    private val reactionLimiter = com.watchtogether.app.dsa.TokenBucketLimiter(capacity = 6.0, refillRatePerSecond = 2.5)

    private val tick = object : Runnable {
        override fun run() {
            if (relayClient.host && relayClient.connected) {
                playerManager.player?.let {
                    relayClient.send(JSONObject().put("type", "tick").put("position", it.currentPosition))
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    private fun triggerHapticFeedback(isStrong: Boolean = false) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                val duration = if (isStrong) 50L else 20L
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density

        movieChannel = MovieStreamChannel(this)

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            fitsSystemWindows = true
        }

        buildHeaderUI(density)
        buildSetupFlowUI(density)
        buildPlayerUI(density)
        buildControlsUI(density)

        initSubsystems(density)

        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        rootLayout.addView(mainLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootLayout.addView(chatController.buildChatSheet())

        setContentView(rootLayout)
        selectTab(true)
        ui.post(tick)

        lobbyManager.updateOnlineFriendsUI()
        val name = lobbyManager.getUserName()
        if (name.isEmpty()) {
            ui.postDelayed({ lobbyManager.showEditNameDialog(initial = true) }, 500)
        } else {
            ui.postDelayed({ lobbyManager.connectLobby() }, 500)
        }
    }

    private fun buildHeaderUI(density: Float) {
        sessionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 10f)
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
            background = UIUtils.roundedBackground(density, Color.parseColor("#334155"), 6f)
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        friendsHeaderBtn = UIUtils.styledButton(this, density, "👥 Friends", Color.parseColor("#059669")) {
            lobbyManager.showFriendsInviteDialog()
        }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        val shareHeaderBtn = UIUtils.styledButton(this, density, "📤 Invite", Color.parseColor("#6366F1")) {
            if (relayClient.invite.isNotEmpty()) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${relayClient.base}|${relayClient.invite}")
                }, "Invite your friend"))
            }
        }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        val pipBtn = UIUtils.styledButton(this, density, "⤢ PiP", Color.parseColor("#334155")) {
            enterPipMode()
        }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        val leaveBtn = UIUtils.styledButton(this, density, "✕ Leave", Color.parseColor("#DC2626")) { leave() }.apply {
            textSize = 11f
            setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topBtnRow.addView(roomCodeBadge)
        topBtnRow.addView(friendsHeaderBtn)
        topBtnRow.addView(shareHeaderBtn)
        topBtnRow.addView(pipBtn)
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
    }

    private fun buildSetupFlowUI(density: Float) {
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
        val userBadge = TextView(this).apply {
            val name = getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE).getString("user_name", "") ?: ""
            text = "👤 ${if (name.isNotEmpty()) name else "Set Name"} ✏️"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f, Color.parseColor("#0284C7"), 1)
            setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
            setOnClickListener { lobbyManager.showEditNameDialog(initial = false) }
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

        // Online Friends Card
        val friendsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f, Color.parseColor("#334155"), 1)
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
        val onlineFriendsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        friendsScroll.addView(onlineFriendsContainer)
        friendsCard.addView(friendsScroll)
        setupContent.addView(friendsCard)

        // 2-Way Tab Switcher
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f)
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

        // Host Container
        hostContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Modern Movie Hero Card
        val movieHeroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#131B2A"), 16f, Color.parseColor("#3B82F6"), 1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (4 * density).toInt(), 0, (10 * density).toInt())
            }
        }
        val movieHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val movieIconBadge = TextView(this).apply {
            text = "🎬"
            textSize = 24f
            setPadding(0, 0, (10 * density).toInt(), 0)
        }
        fileLabel = TextView(this).apply {
            text = "No movie selected\nTap 'Select Video' to begin"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickMovieBtn = UIUtils.styledButton(this, density, "📁 Select Video", Color.parseColor("#2563EB")) {
            if (!relayClient.connected && !relayClient.connecting) pickMovie(autoCreate = false)
        }.apply {
            textSize = 12f
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        movieHeaderRow.addView(movieIconBadge)
        movieHeaderRow.addView(fileLabel)
        movieHeaderRow.addView(pickMovieBtn)
        movieHeroCard.addView(movieHeaderRow)

        val launchPartyBtn = UIUtils.gradientButton(
            this,
            density,
            "🚀  Launch Watch Party",
            Color.parseColor("#4F46E5"),
            Color.parseColor("#7C3AED")
        ) {
            processCreateRoom()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (14 * density).toInt()
            }
        }
        movieHeroCard.addView(launchPartyBtn)
        hostContainer.addView(movieHeroCard)

        // Collapsible Advanced Server Settings
        val advancedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#0F172A"), 12f, Color.parseColor("#223048"), 1)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * density).toInt()
            }
        }
        val prefServer = getPreferences(0).getString("server", DEFAULT_SERVER)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        val savedServer = if (prefServer.contains("trycloudflare.com")) DEFAULT_SERVER else prefServer
        server = EditText(this).apply {
            hint = "Relay Server HTTPS origin"
            setSingleLine(true)
            setText(savedServer)
            inputType = 17
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = UIUtils.roundedBackground(density, Color.parseColor("#080B11"), 8f, Color.parseColor("#334155"), 1)
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
            background = UIUtils.roundedBackground(density, Color.parseColor("#080B11"), 8f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        advancedContent.addView(server)
        advancedContent.addView(UIUtils.spacer(this, density, 8))
        advancedContent.addView(creatorKey)

        val advancedToggleBtn = TextView(this).apply {
            text = "⚙️ Advanced Relay Settings  ▾"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                if (advancedContent.visibility == View.VISIBLE) {
                    advancedContent.visibility = View.GONE
                    text = "⚙️ Advanced Relay Settings  ▾"
                } else {
                    advancedContent.visibility = View.VISIBLE
                    text = "⚙️ Advanced Relay Settings  ▴"
                }
            }
        }
        hostContainer.addView(advancedToggleBtn)
        hostContainer.addView(advancedContent)
        setupContent.addView(hostContainer)

        // Join Container
        joinContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Smart Clipboard Auto-Detect Card
        clipboardAutoJoinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#131B2A"), 14f, Color.parseColor("#10B981"), 2)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, (2 * density).toInt(), 0, (8 * density).toInt())
            }
        }
        val clipAutoTitle = TextView(this).apply {
            text = "✨ Watch Party Invite Found on Clipboard!"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        val clipAutoBtn = UIUtils.gradientButton(
            this,
            density,
            "🍿 Tap to Join Friend Now",
            Color.parseColor("#059669"),
            Color.parseColor("#10B981")
        ) {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (clip.contains("|")) {
                inviteInput.setText(clip)
                doJoin(clip)
            }
        }.apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (8 * density).toInt()
            }
        }
        clipboardAutoJoinCard.addView(clipAutoTitle)
        clipboardAutoJoinCard.addView(clipAutoBtn)
        joinContainer.addView(clipboardAutoJoinCard)

        val joinStep1 = UIUtils.stepCard(this, density, "1", "Friend's Invite Link", "Enter or paste the invite code from your friend")
        inviteInput = EditText(this).apply {
            hint = "server|room.token"
            setSingleLine(true)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            background = UIUtils.roundedBackground(density, Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            val lastInvite = getPreferences(0).getString("last_invite", "") ?: ""
            if (lastInvite.isNotEmpty()) setText(lastInvite)
        }
        val pasteBtn = UIUtils.styledButton(this, density, "📋 Paste from Clipboard", Color.parseColor("#334155")) {
            try {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!clip.isNullOrEmpty()) {
                    inviteInput.setText(clip)
                    note("Invite pasted! Tap 'Connect & Watch' below.")
                } else note("Clipboard is empty.")
            } catch (_: Exception) { note("Cannot read clipboard") }
        }
        joinStep1.addView(inviteInput)
        joinStep1.addView(UIUtils.spacer(this, density, 8))
        joinStep1.addView(pasteBtn)
        joinContainer.addView(joinStep1)

        val joinStep2 = UIUtils.stepCard(this, density, "2", "Connect & Watch", "")
        val joinBtn = UIUtils.gradientButton(
            this,
            density,
            "⚡  Connect & Watch Together",
            Color.parseColor("#2563EB"),
            Color.parseColor("#1D4ED8")
        ) {
            if (!relayClient.connected && !relayClient.connecting) joinRoom()
        }
        joinStep2.addView(joinBtn)
        joinContainer.addView(joinStep2)

        val dataSaverCard = UIUtils.stepCard(this, density, "💾", "Data Saver Option (Optional)", "Have this movie on your phone? Play locally to use 0 MB video data!")
        val pickGuestMovieBtn = UIUtils.styledButton(this, density, "📁 Pick Local Movie Copy (0 MB Data)", Color.parseColor("#059669")) {
            pickGuestLocalMovie()
        }
        guestMovieLabel = TextView(this).apply {
            text = "Default: Streams movie from friend over internet.\nTip: If you already have this movie or received it via Quick Share / WhatsApp / Xender, select it here to watch together with 0 MB internet video data!"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }
        clearGuestMovieBtn = UIUtils.styledButton(this, density, "✕ Switch Back to Cloud Stream", Color.parseColor("#475569")) {
            guestMovie = null
            guestMovieLabel.text = "Default: Streams movie from friend over internet.\nTip: If you already have this movie or received it via Quick Share / WhatsApp / Xender, select it here to watch together with 0 MB internet video data!"
            guestMovieLabel.setTextColor(Color.parseColor("#94A3B8"))
            clearGuestMovieBtn.visibility = View.GONE
            note("Switched back to Cloud Streaming")
        }.apply { visibility = View.GONE }
        dataSaverCard.addView(pickGuestMovieBtn)
        dataSaverCard.addView(guestMovieLabel)
        dataSaverCard.addView(clearGuestMovieBtn)
        joinContainer.addView(dataSaverCard)

        val joinStep3 = UIUtils.stepCard(this, density, "3", "Watch in Sync", "Video streams directly from your friend in real-time")
        val joinHelp = TextView(this).apply {
            text = "No movie file needed on your phone. Playback stays in lockstep automatically."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }
        joinStep3.addView(joinHelp)
        joinContainer.addView(joinStep3)
        setupContent.addView(joinContainer)

        status = TextView(this).apply {
            text = "Ready · Step 1: Choose a movie to begin"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#0F172A"), 10f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        }
        setupContent.addView(status)

        setupScrollView.addView(setupContent)
        mainLayout.addView(setupScrollView)

        // Connect views to LobbyManager
        lobbyManager = LobbyManager(
            activity = this,
            client = client,
            getEndpoint = { endpoint() },
            getRoomDetails = { Pair(relayClient.code, relayClient.invite) },
            isRoomActive = { relayClient.connected && relayClient.host },
            onAcceptInvite = { invitePayload ->
                if (relayClient.connected) leave()
                val fullInvite = "${relayClient.base}|$invitePayload"
                inviteInput.setText(fullInvite)
                selectTab(false)
                doJoin(fullInvite)
            },
            onNote = { note(it) }
        )
        lobbyManager.initViews(userBadge, onlineFriendsContainer, friendsHeaderBtn)
    }

    private fun buildPlayerUI(density: Float) {
        playerContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
        }

        video = PlayerView(this).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        playerContainer.addView(video)

        gestureController = PlayerGestureController(
            activity = this,
            onSeekRelative = { deltaMs -> playerManager.seekRelative(deltaMs, relayClient.connected) },
            onToggleControls = { toggleControlsVisibility() }
        )
        playerContainer.addView(gestureController.buildTouchOverlay())
        playerContainer.addView(gestureController.buildRippleContainer())

        emojiContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = false
            isFocusable = false
        }
        playerContainer.addView(emojiContainer)
        emojiManager = FloatingEmojiManager(this, emojiContainer)

        playerContainer.addView(gestureController.buildHudContainer())

        playerManager = SyncPlayerManager(
            context = this,
            videoView = video,
            onSendControl = { pos, playing -> relayClient.send(JSONObject().put("type", "control").put("position", pos).put("playing", playing)) },
            onSendReady = { ready, bufferPct -> relayClient.send(JSONObject().put("type", "ready").put("ready", ready).put("buffer", bufferPct)) },
            onNote = { note(it) },
            onResetControlsTimer = { resetControlsTimer() },
            onSetControlsVisible = { setControlsVisible(it) }
        )
        playerContainer.addView(playerManager.buildLoadingHud())

        dataSaverBadge = TextView(this).apply {
            text = "💾 0 MB Video Data Active"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#D9065F46"), 10f, Color.parseColor("#34D399"), 1)
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = (10 * density).toInt()
                marginStart = (12 * density).toInt()
            }
            visibility = if (guestMovie != null) View.VISIBLE else View.GONE
            elevation = 15f
        }
        playerContainer.addView(dataSaverBadge)

        reconnectBanner = TextView(this).apply {
            text = "⚠️ Connection Interrupted · Tap to Reconnect ⚡"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#EEB91C1C"), 12f, Color.parseColor("#FCA5A5"), 1)
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (10 * density).toInt()
            }
            visibility = View.GONE
            elevation = 20f
            setOnClickListener {
                it.visibility = View.GONE
                note("⚡ Reconnecting now…")
                relayClient.connect(isReconnect = true)
            }
        }
        playerContainer.addView(reconnectBanner)

        floatingMicBtn = TextView(this).apply {
            text = "🎙️"
            textSize = 17f
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#CC059669"), 18f, Color.parseColor("#34D399"), 1)
            layoutParams = FrameLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = (72 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            elevation = 15f
            setOnClickListener { voiceManager.toggleOpenMic() }
            setOnLongClickListener { performClick(); true }
        }
        playerContainer.addView(floatingMicBtn)

        chatController = ChatController(
            activity = this,
            onGetUserName = { lobbyManager.getUserName().ifEmpty { if (relayClient.host) "Host" else "Friend" } },
            onSendMessage = { text ->
                val myName = lobbyManager.getUserName().ifEmpty { if (relayClient.host) "Host" else "Friend" }
                relayClient.sendChat(text, myName)
            }
        )

        playerContainer.addView(chatController.buildFloatingChatBtn())
        playerContainer.addView(chatController.buildFloatingChatToast())

        mainLayout.addView(playerContainer)
    }

    private fun buildControlsUI(density: Float) {
        controlsGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#E61E293B"), 12f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
        }

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
                setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (!reactionLimiter.tryAcquire(1.0)) return@setOnClickListener
                    triggerHapticFeedback(false)
                    emojiManager.spawn(emoji)
                    animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()
                    relayClient.send(JSONObject().put("type", "reaction").put("emoji", emoji))
                    resetControlsTimer()
                }
            }
            reactionBar.addView(emojiBtn)
        }

        val micBtn = TextView(this).apply {
            text = "🎙️ Talk"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#059669"), 14f)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
            setOnTouchListener { v, event ->
                resetControlsTimer()
                voiceManager.handleMicTouchEvent(v, event)
            }
        }
        reactionBar.addView(micBtn)

        val openMicBtn = TextView(this).apply {
            text = "🔓 Open"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#334155"), 14f)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { voiceManager.toggleOpenMic() }
        }
        reactionBar.addView(openMicBtn)
        reactionBar.addView(chatController.buildChatToggleBtn())
        controlsGroup.addView(reactionBar)

        seekbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val timeCurrent = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeCurrent.text = UIUtils.formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {
                    playerManager.isUserSeeking = true
                    ui.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    playerManager.isUserSeeking = false
                    val target = sb.progress.toLong()
                    playerManager.player?.seekTo(target)
                    playerManager.control(target, playerManager.player?.playWhenReady ?: false, relayClient.connected)
                }
            })
        }
        val timeTotal = TextView(this).apply {
            text = "--:--"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        seekbarRow.addView(timeCurrent)
        seekbarRow.addView(seekBar)
        seekbarRow.addView(timeTotal)
        controlsGroup.addView(seekbarRow)

        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun ctrlParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (4 * density).toInt()
            marginEnd = (4 * density).toInt()
        }
        val b30 = UIUtils.styledButton(this, density, "-30s", Color.parseColor("#334155")) {
            playerManager.seekRelative(-30000L, relayClient.connected)
        }.apply { layoutParams = ctrlParams(); textSize = 12f; setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt()) }
        val bPlay = UIUtils.styledButton(this, density, "▶ Play", Color.parseColor("#2563EB")) {
            val p = playerManager.player
            if (p != null) {
                if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) p.seekTo(0L)
                playerManager.control(p.currentPosition, true, relayClient.connected)
            }
        }.apply { layoutParams = ctrlParams(); textSize = 13f; setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt()) }
        val bPause = UIUtils.styledButton(this, density, "⏸ Paused", Color.parseColor("#DC2626")) {
            playerManager.player?.let { playerManager.control(it.currentPosition, false, relayClient.connected) }
        }.apply { layoutParams = ctrlParams(); textSize = 13f; setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt()) }
        val bPlus30 = UIUtils.styledButton(this, density, "+30s", Color.parseColor("#334155")) {
            playerManager.seekRelative(30000L, relayClient.connected)
        }.apply { layoutParams = ctrlParams(); textSize = 12f; setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt()) }

        bottomControls.addView(b30)
        bottomControls.addView(bPlay)
        bottomControls.addView(bPause)
        bottomControls.addView(bPlus30)
        controlsGroup.addView(bottomControls)

        mainLayout.addView(controlsGroup)

        // Connect views to PlayerManager
        playerManager.bPlay = bPlay
        playerManager.bPause = bPause
        playerManager.seekBar = seekBar
        playerManager.timeCurrent = timeCurrent
        playerManager.timeTotal = timeTotal

        // Connect views to VoiceManager
        voiceManager = VoiceManager(
            activity = this,
            onSendJson = { relayClient.send(it) },
            onSetPlayerVolume = { playerManager.setVolume(it) },
            onShowHud = { gestureController.showHud(it) },
            onHideHudDelayed = { gestureController.hideHudDelayed() },
            onNote = { note(it) }
        )
        voiceManager.initButtons(micBtn, openMicBtn, floatingMicBtn)
    }

    private fun initSubsystems(density: Float) {
        relayClient = RelayClient(client, object : RelayClient.Listener {
            override fun onConnected() {
                playerManager.setupPlayer(
                    client = client,
                    token = relayClient.token,
                    isHost = relayClient.host,
                    code = relayClient.code,
                    base = relayClient.base,
                    movie = movie,
                    guestMovie = guestMovie
                )
                updateScreenMode()
                if (::reconnectBanner.isInitialized) reconnectBanner.visibility = View.GONE
                if (::dataSaverBadge.isInitialized) dataSaverBadge.visibility = if (guestMovie != null) View.VISIBLE else View.GONE
                note(if (relayClient.host) "🟢 Ready! Tap Play to preview or share invite." else "🟢 Joined! Buffering movie…")
            }

            override fun onState(data: JSONObject) {
                playerManager.applyState(data, relayClient.host, relayClient.code, guestMovie != null)
            }

            override fun onReaction(emoji: String) {
                emojiManager.spawn(emoji)
                triggerHapticFeedback(false)
            }

            override fun onChat(senderName: String, text: String, isSelf: Boolean) {
                chatController.handleIncomingChat(senderName, text, isSelf)
                if (!isSelf) triggerHapticFeedback(true)
            }

            override fun onVoice(audioBase64: String) {
                voiceManager.playIncomingVoice(audioBase64)
            }

            override fun onVoiceChunk(audioBase64: String, isSpeaking: Boolean) {
                voiceManager.handleIncomingVoiceChunk(audioBase64, isSpeaking)
            }

            override fun onReadChunk(request: JSONObject) {
                val src = movie
                val srcSize = size
                if (src != null) {
                    val ws = relayClient.socket
                    if (ws != null) {
                        videoWorker.execute { movieChannel.readChunk(ws, request, src, srcSize) }
                    }
                }
            }

            override fun onError(message: String) { note(message) }

            override fun onRoomExpired(wasHost: Boolean) {
                leave()
                if (wasHost) {
                    note("Room expired on server. Tap 'Process & Create Room' to start fresh.")
                } else {
                    note("Room expired or closed by host. Ask host for a new invite link.")
                }
            }

            override fun onHostClosed() {
                leave()
                note("Host closed the room.")
            }

            override fun onNote(message: String) {
                note(message)
                if (message.contains("Reconnecting", ignoreCase = true) || message.contains("Connection interrupted", ignoreCase = true)) {
                    if (::reconnectBanner.isInitialized && relayClient.token.isNotEmpty()) {
                        reconnectBanner.text = "⚠️ Connection Interrupted · Tap to Reconnect ⚡"
                        reconnectBanner.visibility = View.VISIBLE
                    }
                }
            }

            override fun onLeftRoom() { leave() }
        })
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        ui.removeCallbacks(hideControlsRunnable)
        if (visible) {
            controlsGroup.animate().alpha(1f).setDuration(180).withStartAction {
                controlsGroup.visibility = View.VISIBLE
            }.start()
        } else {
            controlsGroup.animate().alpha(0f).setDuration(240).withEndAction {
                controlsGroup.visibility = View.GONE
            }.start()
        }
    }

    private fun toggleControlsVisibility() {
        if (!relayClient.connected) return
        setControlsVisible(!controlsVisible)
        if (controlsVisible && playerManager.player?.playWhenReady == true) {
            resetControlsTimer()
        }
    }

    private fun resetControlsTimer() {
        ui.removeCallbacks(hideControlsRunnable)
        if (playerManager.player?.playWhenReady == true) {
            ui.postDelayed(hideControlsRunnable, 4000)
        }
    }

    private fun checkClipboardInvite() {
        try {
            if (!::clipboardAutoJoinCard.isInitialized) return
            val clip = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (clip.contains("|") && clip.contains(".") && clip.length > 15) {
                clipboardAutoJoinCard.visibility = View.VISIBLE
            } else {
                clipboardAutoJoinCard.visibility = View.GONE
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        checkClipboardInvite()
    }

    private fun selectTab(isHost: Boolean) {
        val density = resources.displayMetrics.density
        if (isHost) {
            hostTab.background = UIUtils.roundedBackground(density, Color.parseColor("#4F46E5"), 10f)
            hostTab.setTextColor(Color.WHITE)
            joinTab.background = UIUtils.roundedBackground(density, Color.parseColor("#131B2A"), 10f)
            joinTab.setTextColor(Color.parseColor("#94A3B8"))
            hostContainer.visibility = View.VISIBLE
            joinContainer.visibility = View.GONE
            note("Host mode · Step 1: Choose a movie to begin")
        } else {
            joinTab.background = UIUtils.roundedBackground(density, Color.parseColor("#4F46E5"), 10f)
            joinTab.setTextColor(Color.WHITE)
            hostTab.background = UIUtils.roundedBackground(density, Color.parseColor("#131B2A"), 10f)
            hostTab.setTextColor(Color.parseColor("#94A3B8"))
            hostContainer.visibility = View.GONE
            joinContainer.visibility = View.VISIBLE
            checkClipboardInvite()
            note("Join mode · Step 1: Paste friend's invite to begin")
        }
    }

    private fun updateScreenMode() {
        ui.post {
            if (destroyed) return@post
            val density = resources.displayMetrics.density
            if (relayClient.connected) {
                setupScrollView.visibility = View.GONE
                playerContainer.visibility = View.VISIBLE
                sessionHeader.visibility = View.VISIBLE
                controlsGroup.visibility = View.VISIBLE
                setControlsVisible(true)
                roomCodeBadge.text = "Room #${if (relayClient.code.length > 8) relayClient.code.take(6) + "…" else relayClient.code}"
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
        val isLocal = url != null && (url.host == "localhost" || url.host == "127.0.0.1" || url.host.startsWith("192.168.") || url.host.startsWith("10."))
        require(url != null && (url.isHttps || isLocal) && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) {
            "Enter only a valid HTTPS server or local network origin, without a path or credentials"
        }
        val value = url.toString().trimEnd('/')
        getPreferences(0).edit().putString("server", value).apply()
        return value
    }

    private fun pickMovie(autoCreate: Boolean = false) {
        if (!relayClient.connected && !relayClient.connecting) {
            autoCreateAfterPick = autoCreate
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/octet-stream"))
            }, 10)
        }
    }

    private fun pickGuestLocalMovie() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/octet-stream"))
        }, 11)
    }

    private fun processCreateRoom() {
        if (relayClient.connected || relayClient.connecting) return
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
        if (resultCode != RESULT_OK) return
        if (requestCode == 11) {
            try {
                val uri = data?.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                var name = "Local Movie"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
                guestMovie = uri
                guestMovieLabel.text = "✓ Using local file: $name\n(0 MB Video Data · Instant Playback!)"
                guestMovieLabel.setTextColor(Color.parseColor("#34D399"))
                clearGuestMovieBtn.visibility = View.VISIBLE
                note("Local movie selected! 0 MB internet video data will be used.")
            } catch (e: Exception) { note(e.message ?: "Cannot open local movie") }
            return
        }
        if (requestCode != 10) return
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
            movieChannel.close()
            movie = uri
            size = length
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
            fileLabel.text = "🎬 $name\n${size / 1024 / 1024} MB · Ready to Stream"
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

            relayClient.createRoom(
                serverEndpoint = endpoint(),
                creatorKey = key,
                fileSize = size,
                mimeType = mime,
                onSuccess = { code, invite ->
                    if (lobbyManager.onlineFriends.isNotEmpty()) {
                        note("🟢 Room created! Tap '👥 Friends' to invite ${lobbyManager.onlineFriends[0].optString("name")}, or use '📤 Invite'.")
                    } else {
                        try {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${relayClient.base}|$invite")
                            }, "Invite your friend"))
                        } catch (_: Exception) {}
                    }
                },
                onFailure = { note(it) }
            )
            note("Creating private room…")
        } catch (e: Exception) { note(e.message ?: "Unable to create room") }
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
            server.setText(input[0].trim())
            val base = endpoint()
            val parts = input[1].trim().split('.')
            require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Invalid invite" }
            val roomCode = parts[0]
            val joinToken = parts[1]

            relayClient.joinRoom(
                serverEndpoint = base,
                roomCode = roomCode,
                joinToken = joinToken,
                onSuccess = { remoteMime, remoteSize ->
                    if (remoteMime.isNotEmpty()) playerManager.remoteMime = remoteMime
                    if (remoteSize > 0L) playerManager.remoteSize = remoteSize
                    inviteInput.text.clear()
                },
                onFailure = { note(it) }
            )
            note("Connecting to friend's movie…")
        } catch (e: Exception) { note(e.message ?: "Invalid invite") }
    }

    private fun leave() {
        relayClient.leave()
        playerManager.release()
        voiceManager.reset()
        chatController.reset()
        emojiManager.clear()
        movieChannel.close()
        inviteInput.text.clear()
        if (::reconnectBanner.isInitialized) reconnectBanner.visibility = View.GONE
        if (::dataSaverBadge.isInitialized) dataSaverBadge.visibility = View.GONE
        updateScreenMode()
        note("Left room · Ready for next movie")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        chatController.onConfigurationChanged(newConfig)
    }

    override fun onBackPressed() {
        if (chatController.onBackPressed()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
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

    private fun enterPipMode() {
        try {
            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (relayClient.connected && playerManager.player != null) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            sessionHeader.visibility = View.GONE
            controlsGroup.visibility = View.GONE
            if (::floatingMicBtn.isInitialized) floatingMicBtn.visibility = View.GONE
            if (::chatController.isInitialized) {
                chatController.chatSheet.visibility = View.GONE
                chatController.floatingChatBtn.visibility = View.GONE
                chatController.floatingChatToast.visibility = View.GONE
            }
        } else {
            if (relayClient.connected) {
                sessionHeader.visibility = View.VISIBLE
                controlsGroup.visibility = View.VISIBLE
                if (::floatingMicBtn.isInitialized) floatingMicBtn.visibility = View.VISIBLE
                if (::chatController.isInitialized) chatController.floatingChatBtn.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacks(tick)
        ui.removeCallbacks(hideControlsRunnable)
        relayClient.destroy()
        playerManager.destroy()
        voiceManager.destroy()
        chatController.destroy()
        gestureController.destroy()
        lobbyManager.destroy()
        movieChannel.close()
        videoWorker.shutdownNow()
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
