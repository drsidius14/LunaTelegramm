package ru.luna.telegram

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var action: Button
    private lateinit var groups: LinearLayout
    private lateinit var selected: TextView
    private lateinit var apiInfo: TextView

    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()

    private var selectedChatId = 0L
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

        if (BuildConfig.TELEGRAM_API_ID == 0 ||
            BuildConfig.TELEGRAM_API_HASH.isBlank()
        ) {
            status.text =
                "Не заданы TELEGRAM_API_ID и TELEGRAM_API_HASH.\nПроверь GitHub Secrets."
            action.isEnabled = false
            return
        }

        Client.execute(TdApi.SetLogVerbosityLevel(0))

        td = Client.create(
            { obj ->
                if (obj is TdApi.Update) {
                    handleUpdate(obj)
                }
            },
            null,
            null
        )

        bridge = LocalBridge(
            client = td,
            port = 8765,
            token = getOrCreateToken()
        ) {
            selectedChatId
        }

        bridge.start()

        apiInfo.text =
            "Luna API: http://127.0.0.1:8765/\nToken: ${bridge.token}"

        action.setOnClickListener {
            submitAuthInput()
        }

        status.text = "Инициализация Telegram…"
    }

    private fun submitAuthInput() {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return

        when (authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                td.send(
                    TdApi.SetAuthenticationPhoneNumber(value, null),
                    AuthHandler()
                )
            }

            is TdApi.AuthorizationStateWaitCode -> {
                td.send(
                    TdApi.CheckAuthenticationCode(value),
                    AuthHandler()
                )
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                td.send(
                    TdApi.CheckAuthenticationPassword(value),
                    AuthHandler()
                )
            }

            is TdApi.AuthorizationStateWaitEmailAddress -> {
                td.send(
                    TdApi.SetAuthenticationEmailAddress(value),
                    AuthHandler()
                )
            }

            is TdApi.AuthorizationStateWaitEmailCode -> {
                td.send(
                    TdApi.CheckAuthenticationEmailCode(
                        TdApi.EmailAddressAuthenticationCode(value)
                    ),
                    AuthHandler()
                )
            }

            else -> Unit
        }

        input.text.clear()
    }

    private fun handleUpdate(update: TdApi.Update) {
        runOnUiThread {
            when (update) {

                is TdApi.UpdateAuthorizationState -> {
                    authState = update.authorizationState

                    when (val state = update.authorizationState) {

                        is TdApi.AuthorizationStateWaitTdlibParameters -> {
                            val request = TdApi.SetTdlibParameters()

                            request.useTestDc = false
                            request.databaseDirectory =
                                filesDir.absolutePath + "/td"
                            request.filesDirectory =
                                filesDir.absolutePath + "/td/files"

                            request.useFileDatabase = true
                            request.useChatInfoDatabase = true
                            request.useMessageDatabase = true
                            request.useSecretChats = false

                            request.apiId = BuildConfig.TELEGRAM_API_ID
                            request.apiHash = BuildConfig.TELEGRAM_API_HASH

                            request.systemLanguageCode = "ru"
                            request.deviceModel = android.os.Build.MODEL
                            request.systemVersion =
                                android.os.Build.VERSION.RELEASE
                            request.applicationVersion = "0.1-final"

                            request.databaseEncryptionKey = ByteArray(0)
                            request.enableStorageOptimizer = true

                            td.send(request, AuthHandler())
                        }

                        is TdApi.AuthorizationStateWaitPhoneNumber -> {
                            status.text =
                                "Введи номер Telegram в международном формате."
                            input.hint = "+79991234567"
                            input.inputType =
                                InputType.TYPE_CLASS_PHONE
                        }

                        is TdApi.AuthorizationStateWaitCode -> {
                            status.text = "Введи код Telegram."
                            input.hint = "Код"
                            input.inputType =
                                InputType.TYPE_CLASS_NUMBER
                        }

                        is TdApi.AuthorizationStateWaitPassword -> {
                            status.text =
                                "Введи пароль двухэтапной проверки Telegram."
                            input.hint = "2FA пароль"
                            input.inputType =
                                InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }

                        is TdApi.AuthorizationStateWaitEmailAddress -> {
                            status.text = "Введи e-mail Telegram."
                            input.hint = "E-mail"
                            input.inputType =
                                InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        }

                        is TdApi.AuthorizationStateWaitEmailCode -> {
                            status.text = "Введи код из e-mail Telegram."
                            input.hint = "Код"
                            input.inputType =
                                InputType.TYPE_CLASS_NUMBER
                        }

                        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                            status.text =
                                "Подтверди вход на другом устройстве Telegram."
                            input.isEnabled = false
                            action.isEnabled = false
                        }

                        is TdApi.AuthorizationStateReady -> {
                            input.isEnabled = false
                            action.isEnabled = false
                            status.text =
                                "Telegram подключён. Загружаю группы…"
                            loadChats()
                        }

                        is TdApi.AuthorizationStateLoggingOut -> {
                            status.text = "Выход из Telegram…"
                        }

                        is TdApi.AuthorizationStateClosing -> {
                            status.text = "Закрытие TDLib…"
                        }

                        is TdApi.AuthorizationStateClosed -> {
                            status.text = "Сессия закрыта."
                        }

                        else -> Unit
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

                else -> Unit
            }
        }
    }

    private fun loadChats() {
        td.send(
            TdApi.LoadChats(
                TdApi.ChatListMain(),
                100
            ),
            object : Client.ResultHandler {
                override fun onResult(obj: TdApi.Object) {
                    if (obj is TdApi.Error && obj.code != 404) {
                        runOnUiThread {
                            status.text =
                                "Ошибка загрузки групп: ${obj.message}"
                        }
                    }
                }
            }
        )
    }

    private fun addOrRefreshGroup(chat: TdApi.Chat) {
        val isGroup =
            chat.type is TdApi.ChatTypeBasicGroup ||
            chat.type is TdApi.ChatTypeSupergroup

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

            selected.text =
                "Выбранная группа: ${chat.title}\nID: ${chat.id}"

            status.text = "Группа выбрана. Luna API готов."
        }
    }

    private fun getOrCreateToken(): String {
        val prefs =
            getSharedPreferences("luna", MODE_PRIVATE)

        var token = prefs.getString("token", null)

        if (token == null) {
            token = UUID.randomUUID()
                .toString()
                .replace("-", "")

            prefs.edit()
                .putString("token", token)
                .apply()
        }

        return token
    }

    private class AuthHandler : Client.ResultHandler {
        override fun onResult(obj: TdApi.Object) {
            // Результат авторизации приходит через
            // UpdateAuthorizationState.
        }
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) {
            bridge.stop()
        }

        if (::td.isInitialized) {
            td.send(TdApi.Close(), null)
        }

        super.onDestroy()
    }
}
