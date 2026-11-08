package com.watchtogether.app.lobby

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.watchtogether.app.ui.UIUtils
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LobbyManager(
    private val activity: Activity,
    private val client: OkHttpClient,
    private val getEndpoint: () -> String,
    private val getRoomDetails: () -> Pair<String, String>, // Pair(code, invite)
    private val isRoomActive: () -> Boolean,
    private val onAcceptInvite: (invitePayload: String) -> Unit,
    private val onNote: (String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    private var lobbySocket: WebSocket? = null
    private val lobbyReconnectTask = Runnable { connectLobby() }
    var onlineFriends = mutableListOf<JSONObject>()
        private set

    lateinit var userBadge: TextView
    lateinit var onlineFriendsContainer: LinearLayout
    lateinit var friendsHeaderBtn: TextView

    private var isDestroyed = false

    fun initViews(badge: TextView, friendsContainer: LinearLayout, friendsBtn: TextView) {
        userBadge = badge
        onlineFriendsContainer = friendsContainer
        friendsHeaderBtn = friendsBtn
    }

    fun getUserName(): String {
        return activity.getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
    }

    fun setUserName(name: String) {
        activity.getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
            .edit().putString("user_name", name.trim()).apply()
    }

    fun getUserId(): String {
        val prefs = activity.getSharedPreferences("watchtogether_prefs", Context.MODE_PRIVATE)
        var uid = prefs.getString("user_id", "") ?: ""
        if (uid.isEmpty()) {
            uid = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("user_id", uid).apply()
        }
        return uid
    }

    fun showEditNameDialog(initial: Boolean = false) {
        if (isDestroyed) return
        val input = EditText(activity).apply {
            hint = "Your name (e.g. Parasuram)"
            setText(getUserName())
            setSingleLine(true)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#0F172A"), 8f, Color.parseColor("#334155"), 1)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle(if (initial) "👋 Welcome! Enter Your Name" else "✏️ Change Display Name")
            .setMessage(if (initial) "Enter your name so friends can see when you're online and invite you directly to watch movies together." else "Your friends will see this name when you are online.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    setUserName(name)
                    if (::userBadge.isInitialized) userBadge.text = "👤 $name ✏️"
                    onNote("Hello, $name! 👋")
                    connectLobby()
                }
            }
            .setCancelable(!initial)
            .show()
    }

    fun connectLobby() {
        if (isDestroyed) return
        val name = getUserName()
        if (name.isEmpty()) return
        val uid = getUserId()
        val lobbyBase = try { getEndpoint() } catch (_: Exception) { "https://watchtogether-relay.onrender.com" }
        val url = lobbyBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/lobby/ws"
        val req = Request.Builder().url(url).build()

        lobbySocket?.close(1000, "Reconnecting")
        lobbySocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ui.post {
                    if (isDestroyed) return@post
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
                                if (!isDestroyed) {
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
                                if (!isDestroyed) {
                                    showIncomingInviteDialog(fromName, fromUid, inviteCode, invitePayload)
                                }
                            }
                        }
                        "invite_response" -> {
                            val accepted = data.optBoolean("accepted")
                            ui.post {
                                if (!isDestroyed) {
                                    if (accepted) {
                                        onNote("🎉 Friend accepted! Connecting to movie…")
                                    } else {
                                        onNote("Friend declined or is busy right now.")
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ui.post {
                    if (!isDestroyed) {
                        ui.removeCallbacks(lobbyReconnectTask)
                        ui.postDelayed(lobbyReconnectTask, 5000L)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ui.post {
                    if (!isDestroyed && code != 1000) {
                        ui.removeCallbacks(lobbyReconnectTask)
                        ui.postDelayed(lobbyReconnectTask, 5000L)
                    }
                }
            }
        })
    }

    fun updateOnlineFriendsUI() {
        if (!::onlineFriendsContainer.isInitialized) return
        onlineFriendsContainer.removeAllViews()
        if (::friendsHeaderBtn.isInitialized) {
            friendsHeaderBtn.text = "👥 Friends (${onlineFriends.size})"
        }

        if (onlineFriends.isEmpty()) {
            val emptyNotice = TextView(activity).apply {
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
            val friendChip = TextView(activity).apply {
                text = "🟢 $fName  ➔ Invite"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = UIUtils.roundedBackground(density, Color.parseColor("#059669"), 14f)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    val (code, invite) = getRoomDetails()
                    if (isRoomActive() && code.isNotEmpty() && invite.isNotEmpty()) {
                        sendPeerInvite(fUid, fName)
                        text = "⏳ Invited $fName…"
                        background = UIUtils.roundedBackground(density, Color.parseColor("#D97706"), 14f)
                    } else {
                        onNote("Tap 'Process & Create Room' to launch movie, then invite $fName!")
                        showFriendsInviteDialog()
                    }
                }
            }
            onlineFriendsContainer.addView(friendChip)
        }
    }

    private fun showIncomingInviteDialog(fromName: String, fromUid: String, inviteCode: String, invitePayload: String) {
        if (isDestroyed) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                (activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
                    android.os.VibrationEffect.createWaveform(longArrayOf(0, 150, 100, 150), -1)
                )
            }
        } catch (_: Exception) {}

        AlertDialog.Builder(activity)
            .setTitle("🎬 Movie Invitation!")
            .setMessage("$fromName invited you to watch a movie together right now!")
            .setPositiveButton("Accept & Watch 🍿") { _, _ ->
                lobbySocket?.send(JSONObject().put("type", "invite_response").put("toUserId", fromUid).put("accepted", true).toString())
                onAcceptInvite(invitePayload)
            }
            .setNegativeButton("Decline") { _, _ ->
                lobbySocket?.send(JSONObject().put("type", "invite_response").put("toUserId", fromUid).put("accepted", false).toString())
            }
            .setCancelable(true)
            .show()
    }

    fun showFriendsInviteDialog() {
        if (isDestroyed) return
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        if (onlineFriends.isEmpty()) {
            val emptyMsg = TextView(activity).apply {
                text = "None of your friends are online in the app right now.\n\nYou can still share the room link via WhatsApp, SMS, or other apps using the button below!"
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            container.addView(emptyMsg)
        } else {
            val tip = TextView(activity).apply {
                text = "Tap a friend to send them a 1-click in-app watch invitation:"
                textSize = 12f
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 0, 0, (8 * density).toInt())
            }
            container.addView(tip)
            for (friend in onlineFriends) {
                val fName = friend.optString("name", "Friend")
                val fUid = friend.optString("id")
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                }
                val friendLabel = TextView(activity).apply {
                    text = "🟢 $fName (Online)"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val inviteBtn = UIUtils.styledButton(activity, density, "🍿 Invite", Color.parseColor("#059669")) {
                    val (code, invite) = getRoomDetails()
                    if (isRoomActive() && code.isNotEmpty() && invite.isNotEmpty()) {
                        sendPeerInvite(fUid, fName)
                        onNote("📨 Sent in-app invite to $fName!")
                    } else {
                        onNote("Create room first to invite $fName")
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

        val shareFallbackBtn = UIUtils.styledButton(activity, density, "📤 Share Link via Other Apps", Color.parseColor("#6366F1")) {
            val (_, invite) = getRoomDetails()
            if (invite.isNotEmpty()) {
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${getEndpoint()}|$invite")
                }, "Invite your friend"))
            } else {
                onNote("Launch a room first to get an invite link.")
            }
        }.apply {
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (10 * density).toInt()
            }
        }
        container.addView(shareFallbackBtn)

        AlertDialog.Builder(activity)
            .setTitle("👥 Online Friends (${onlineFriends.size})")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()
    }

    fun sendPeerInvite(targetUid: String, targetName: String) {
        val (code, invite) = getRoomDetails()
        if (code.isEmpty() || invite.isEmpty()) {
            onNote("Launch a room first to invite $targetName")
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
        onNote("📨 Invited $targetName! Waiting for them to accept…")
    }

    fun destroy() {
        isDestroyed = true
        ui.removeCallbacks(lobbyReconnectTask)
        lobbySocket?.close(1000, "Destroyed")
        lobbySocket = null
    }
}

