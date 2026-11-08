package com.watchtogether.app.chat

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchtogether.app.ui.UIUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatController(
    private val activity: Activity,
    private val onGetUserName: () -> String = { "You" },
    private val onSendMessage: (text: String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    lateinit var chatSheet: LinearLayout
        private set
    lateinit var chatMessagesLayout: LinearLayout
        private set
    lateinit var chatScrollView: ScrollView
        private set
    lateinit var chatInput: EditText
        private set
    lateinit var chatSendBtn: TextView
        private set
    lateinit var chatToggleBtn: TextView
        private set
    lateinit var floatingChatBtn: TextView
        private set
    lateinit var floatingChatToast: LinearLayout
        private set
    lateinit var floatingChatToastText: TextView
        private set

    var isChatOpen = false
        private set
    private var unreadChatCount = 0
    private var isDestroyed = false

    private val hideChatToastRunnable = Runnable {
        if (!isDestroyed && ::floatingChatToast.isInitialized) {
            floatingChatToast.animate().alpha(0f).setDuration(250).withEndAction {
                floatingChatToast.visibility = View.GONE
            }.start()
        }
    }

    fun buildFloatingChatBtn(): TextView {
        floatingChatBtn = TextView(activity).apply {
            text = "💬"
            textSize = 17f
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#CC1E293B"), 18f, Color.parseColor("#3B82F6"), 1)
            layoutParams = FrameLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = (72 * density).toInt()
                marginStart = (14 * density).toInt()
            }
            elevation = 15f
            setOnClickListener { toggleChat() }
        }
        return floatingChatBtn
    }

    fun buildFloatingChatToast(): LinearLayout {
        floatingChatToast = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UIUtils.roundedBackground(density, Color.parseColor("#F00F172A"), 14f, Color.parseColor("#3B82F6"), 1)
            setPadding((12 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            elevation = 20f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = (122 * density).toInt()
                marginStart = (14 * density).toInt()
                marginEnd = (14 * density).toInt()
            }
            setOnClickListener { toggleChat() }
        }
        floatingChatToastText = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        floatingChatToast.addView(floatingChatToastText)
        return floatingChatToast
    }

    fun buildChatToggleBtn(): TextView {
        chatToggleBtn = TextView(activity).apply {
            text = "💬 Chat"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E3A8A"), 8f, Color.parseColor("#3B82F6"), 1)
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * density).toInt()
            }
            setOnClickListener { toggleChat() }
        }
        return chatToggleBtn
    }

    fun buildChatSheet(): LinearLayout {
        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        chatSheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UIUtils.roundedBackground(density, Color.parseColor("#F50F172A"), 16f, Color.parseColor("#334155"), 1)
            elevation = 30f
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                if (isLandscape) (340 * density).toInt() else FrameLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else (300 * density).toInt()
            ).apply {
                gravity = if (isLandscape) Gravity.END or Gravity.BOTTOM else Gravity.BOTTOM
                bottomMargin = (6 * density).toInt()
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
                topMargin = if (isLandscape) (6 * density).toInt() else 0
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            chatSheet.setOnApplyWindowInsetsListener { view, insets ->
                val imeHeight = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val lp = view.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.bottomMargin = (6 * density).toInt() + imeHeight
                    view.layoutParams = lp
                }
                insets
            }
        } else {
            chatSheet.setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val bottom = insets.systemWindowInsetBottom
                val lp = view.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.bottomMargin = (6 * density).toInt() + bottom
                    view.layoutParams = lp
                }
                insets
            }
        }

        val chatHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val chatTitle = TextView(activity).apply {
            text = "💬 Room Chat"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chatCloseBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 10f)
            setOnClickListener { toggleChat() }
        }
        chatHeader.addView(chatTitle)
        chatHeader.addView(chatCloseBtn)
        chatSheet.addView(chatHeader)

        chatScrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        chatMessagesLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val welcomeNotice = TextView(activity).apply {
            text = "👋 Welcome to Room Chat! Messages are live and private to this room."
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        chatMessagesLayout.addView(welcomeNotice)
        chatScrollView.addView(chatMessagesLayout)
        chatSheet.addView(chatScrollView)

        val quickChipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }
        val quickChipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickPhrases = listOf(
            "Haha! 😂",
            "Wait pause ⏸",
            "OMG 🔥",
            "What a scene! 🍿",
            "Who was that? 🤔",
            "Let's go! 🚀"
        )
        for (phrase in quickPhrases) {
            val chip = TextView(activity).apply {
                text = phrase
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E2E8F0"))
                background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f, Color.parseColor("#334155"), 1)
                setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    val myName = onGetUserName().ifEmpty { "You" }
                    handleIncomingChat(myName, phrase, isSelf = true)
                    onSendMessage(phrase)
                }
            }
            quickChipsRow.addView(chip)
        }
        quickChipsScroll.addView(quickChipsRow)
        chatSheet.addView(quickChipsScroll)

        val chatInputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * density).toInt()
            }
        }
        chatInput = EditText(activity).apply {
            hint = "Type a message…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 10f, Color.parseColor("#334155"), 1)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                    sendCurrentChatMessage()
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    sendCurrentChatMessage()
                    true
                } else false
            }
        }
        chatSendBtn = TextView(activity).apply {
            text = "➤"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = UIUtils.roundedBackground(density, Color.parseColor("#2563EB"), 10f)
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
                sendCurrentChatMessage()
            }
        }
        chatInputRow.addView(chatInput)
        chatInputRow.addView(chatSendBtn)
        chatSheet.addView(chatInputRow)

        return chatSheet
    }

    fun toggleChat() {
        if (isDestroyed || !::chatSheet.isInitialized) return
        isChatOpen = !isChatOpen
        if (isChatOpen) {
            unreadChatCount = 0
            floatingChatBtn.text = "💬"
            chatToggleBtn.text = "💬 Chat"
            ui.removeCallbacks(hideChatToastRunnable)
            floatingChatToast.visibility = View.GONE
            chatSheet.visibility = View.VISIBLE
            chatSheet.alpha = 0f
            chatSheet.translationY = 50f
            chatSheet.animate().alpha(1f).translationY(0f).setDuration(180).start()
            chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
            chatInput.postDelayed({
                if (!isDestroyed && isChatOpen) {
                    chatInput.requestFocus()
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 100)
        } else {
            chatSheet.animate().alpha(0f).translationY(50f).setDuration(150).withEndAction {
                chatSheet.visibility = View.GONE
            }.start()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(chatInput.windowToken, 0)
        }
    }

    fun sendCurrentChatMessage() {
        if (isDestroyed || !::chatInput.isInitialized) return
        val rawText = chatInput.text?.toString()?.trim() ?: ""
        if (rawText.isEmpty()) return
        chatInput.setText("")
        val myName = onGetUserName().ifEmpty { "You" }
        handleIncomingChat(myName, rawText, isSelf = true)
        onSendMessage(rawText)
    }

    fun handleIncomingChat(senderName: String, text: String, isSelf: Boolean) {
        ui.post {
            if (isDestroyed || !::chatMessagesLayout.isInitialized) return@post
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

            val bubbleContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isSelf) Gravity.END else Gravity.START
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (3 * density).toInt()
                    bottomMargin = (3 * density).toInt()
                }
            }

            val bubble = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isSelf) {
                    UIUtils.roundedBackground(density, Color.parseColor("#2563EB"), 12f)
                } else {
                    UIUtils.roundedBackground(density, Color.parseColor("#1E293B"), 12f, Color.parseColor("#334155"), 1)
                }
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (isSelf) Gravity.END else Gravity.START
                }
            }

            if (!isSelf) {
                val nameView = TextView(activity).apply {
                    this.text = senderName
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = (2 * density).toInt()
                    }
                }
                bubble.addView(nameView)
            }

            val msgView = TextView(activity).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            bubble.addView(msgView)

            val timeView = TextView(activity).apply {
                this.text = timeStr
                setTextColor(if (isSelf) Color.parseColor("#BFDBFE") else Color.parseColor("#64748B"))
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    topMargin = (2 * density).toInt()
                }
            }
            bubble.addView(timeView)

            bubbleContainer.addView(bubble)
            chatMessagesLayout.addView(bubbleContainer)

            // DSA: Bounded Queue eviction to maintain O(1) view tree size and prevent memory leaks
            if (chatMessagesLayout.childCount > 80) {
                chatMessagesLayout.removeViewAt(1)
            }

            chatScrollView.post {
                chatScrollView.fullScroll(View.FOCUS_DOWN)
            }

            if (!isChatOpen) {
                unreadChatCount++
                floatingChatBtn.text = "💬 $unreadChatCount"
                chatToggleBtn.text = "💬 ($unreadChatCount)"

                floatingChatToastText.text = "💬 $senderName: $text"
                floatingChatToast.visibility = View.VISIBLE
                floatingChatToast.alpha = 1f
                ui.removeCallbacks(hideChatToastRunnable)
                ui.postDelayed(hideChatToastRunnable, 4500)
            }
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        if (::chatSheet.isInitialized) {
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            chatSheet.layoutParams = FrameLayout.LayoutParams(
                if (isLandscape) (340 * density).toInt() else FrameLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) FrameLayout.LayoutParams.MATCH_PARENT else (300 * density).toInt()
            ).apply {
                gravity = if (isLandscape) Gravity.END or Gravity.BOTTOM else Gravity.BOTTOM
                bottomMargin = (6 * density).toInt()
                marginStart = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
                topMargin = if (isLandscape) (6 * density).toInt() else 0
            }
        }
    }

    fun onBackPressed(): Boolean {
        if (isChatOpen) {
            toggleChat()
            return true
        }
        return false
    }

    fun reset() {
        if (isChatOpen) toggleChat()
        unreadChatCount = 0
        if (::floatingChatBtn.isInitialized) floatingChatBtn.text = "💬"
        if (::chatToggleBtn.isInitialized) chatToggleBtn.text = "💬 Chat"
        if (::floatingChatToast.isInitialized) floatingChatToast.visibility = View.GONE
        if (::chatMessagesLayout.isInitialized) {
            chatMessagesLayout.removeAllViews()
            val welcomeNotice = TextView(activity).apply {
                text = "👋 Welcome to Room Chat! Messages are live and private to this room."
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            chatMessagesLayout.addView(welcomeNotice)
        }
    }

    fun destroy() {
        isDestroyed = true
        ui.removeCallbacks(hideChatToastRunnable)
    }
}

