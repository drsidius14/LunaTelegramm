package ru.luna.telegram

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var action: Button
    private lateinit var groups: LinearLayout
    private lateinit var selected: TextView
    private lateinit var apiInfo: TextView

    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private var selectedChatId: Long = 0
    private var authState: TdApi.AuthorizationState? = null
    private lateinit var td: Client
    private lateinit var bridge: LocalBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        action = findViewById(R.id.action)
        groups = findViewById(R.id.groups)
        selected = findViewById(R.id.selected)
        apiInfo = findViewById(R.id.apiInfo)

        Client.execute(TdApi.SetLogVerbosityLevel(0))
        td = Client.create({ update -> handleUpdate(update) }, null, null)

        bridge = LocalBridge(
            port = 8765,
            token = getOrCreateToken()
        ) { selectedChatId }

        bridge.start()
        apiInfo.text = "Luna API: http://0.0.0.0:8765/  token: ${bridge.token}"

        action.setOnClickListener { submitAuthInput() }

        if (BuildConfig.TELEGRAM_API_ID == 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            status.text = "Нужны TELEGRAM_API_ID и TELEGRAM_API_HASH.\nОни берутся на my.telegram.org."
            action.isEnabled = false
            return
        }

        // TDLib will immediately emit authorizationStateWaitTdlibParameters.
    }

    private fun submitAuthInput() {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        when (val s = authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                td.send(TdApi.SetAuthenticationPhoneNumber(value, null), AuthHandler())
            is TdApi.AuthorizationStateWaitCode ->
                td.send(TdApi.CheckAuthenticationCode(value), AuthHandler())
            is TdApi.AuthorizationStateWaitPassword ->
                td.send(TdApi.CheckAuthenticationPassword(value), AuthHandler())
            else -> {}
        }
        input.text.clear()
    }

    private fun handleUpdate(update: TdApi.Update) {
        runOnUiThread {
            when (update) {
                is TdApi.UpdateAuthorizationState -> {
                    authState = update.authorizationState
                    when (val s = update.authorizationState) {
                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            td.send(
                                TdApi.SetTdlibParameters(
                                    false,
                                    filesDir.absolutePath + "/td",
                                    filesDir.absolutePath + "/td/files",
                                    "",
                                    true,
                                    true,
                                    true,
                                    false,
                                    BuildConfig.TELEGRAM_API_ID,
                                    BuildConfig.TELEGRAM_API_HASH,
                                    "ru",
                                    "Android",
                                    android.os.Build.VERSION.RELEASE,
                                    "0.1",
                                    true,
                                    false
                                ), AuthHandler()
                            )
                        }
                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            status.text = "Введи номер Telegram в международном формате."
                            input.hint = "+79991234567"
                        }
                        is TdApi.AuthorizationStateWaitCode -> {
                            status.text = "Введи код Telegram."
                            input.hint = "Код"
                        }
                        is TdApi.AuthorizationStateWaitPassword -> {
                            status.text = "Введи пароль двухэтапной проверки Telegram."
                            input.hint = "2FA пароль"
                        }
                        is TdApi.AuthorizationStateReady -> {
                            status.text = "Telegram подключён. Загружаю группы…"
                            loadChats()
                        }
                        is TdApi.AuthorizationStateLoggingOut -> status.text = "Выход…"
                        is TdApi.AuthorizationStateClosed -> status.text = "Сессия закрыта."
                        else -> {}
                    }
                }
                is TdApi.UpdateNewChat -> {
                    chats[update.chat.id] = update.chat
                    addOrRefreshGroup(update.chat)
                }
                is TdApi.UpdateChatTitle -> {
                    val old = chats[update.chatId]
                    if (old != null) {
                        old.title = update.title
                        addOrRefreshGroup(old)
                    }
                }
            }
        }
    }

    private fun loadChats() {
        td.send(TdApi.GetChats(TdApi.ChatListMain(), 100), object : Client.ResultHandler {
            override fun onResult(obj: TdApi.TLObject) {
                if (obj is TdApi.Chats) {
                    obj.chatIds.forEach { id ->
                        td.send(TdApi.GetChat(id), object : Client.ResultHandler {
                            override fun onResult(chatObj: TdApi.TLObject) {
                                if (chatObj is TdApi.Chat) {
                                    chats[id] = chatObj
                                    runOnUiThread { addOrRefreshGroup(chatObj) }
                                }
                            }
                        })
                    }
                }
            }
        })
    }

    private fun addOrRefreshGroup(chat: TdApi.Chat) {
        val isGroup = chat.type is TdApi.ChatTypeBasicGroup || chat.type is TdApi.ChatTypeSupergroup
        if (!isGroup) return

        val tag = "chat_${chat.id}"
        var button = groups.findViewWithTag<Button>(tag)
        if (button == null) {
            button = Button(this)
            button.tag = tag
            groups.addView(button)
        }
        button.text = chat.title
        button.setOnClickListener {
            selectedChatId = chat.id
            selected.text = "Выбранная группа: ${chat.title}\nID: ${chat.id}"
            status.text = "Группа выбрана. Luna API готов."
        }
    }

    private fun getOrCreateToken(): String {
        val p = getSharedPreferences("luna", MODE_PRIVATE)
        var t = p.getString("token", null)
        if (t == null) {
            t = java.util.UUID.randomUUID().toString().replace("-", "")
            p.edit().putString("token", t).apply()
        }
        return t
    }

    private class AuthHandler : Client.ResultHandler {
        override fun onResult(obj: TdApi.TLObject) {
            if (obj is TdApi.Error) {
                // Authorization state updates provide the next UI step.
            }
        }
    }

    override fun onDestroy() {
        bridge.stop()
        super.onDestroy()
    }
}
