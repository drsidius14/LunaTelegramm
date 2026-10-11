package ru.luna.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocalBridge(
    private val client: Client,
    private val port: Int,
    val token: String,
    private val selectedId: () -> Long
) {

    private var server: ServerSocket? = null

    private val pool =
        Executors.newCachedThreadPool()

    fun start() {
        pool.submit {
            try {
                server = ServerSocket(
                    port,
                    50,
                    InetAddress.getByName("127.0.0.1")
                )

                while (!server!!.isClosed) {
                    val socket = server!!.accept()

                    pool.submit {
                        handle(socket)
                    }
                }
            } catch (_: Exception) {
                // Server stopped.
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }

        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { connection ->

            val reader =
                BufferedReader(
                    InputStreamReader(
                        connection.getInputStream()
                    )
                )

            val request =
                reader.readLine() ?: return

            val headers = mutableListOf<String>()

            while (true) {
                val line = reader.readLine() ?: break

                if (line.isEmpty()) break

                headers += line
            }

            val auth =
                headers.firstOrNull {
                    it.startsWith(
                        "Authorization:",
                        ignoreCase = true
                    )
                }

            if (
                auth?.substringAfter(":")?.trim()
                != "Bearer $token"
            ) {
                respond(
                    connection.getOutputStream(),
                    401,
                    """{"error":"unauthorized"}"""
                )
                return
            }

            val path =
                request
                    .split(" ")
                    .getOrNull(1)
                    ?: "/"

            when {
                path == "/health" -> {
                    respond(
                        connection.getOutputStream(),
                        200,
                        """{"ok":true,"service":"luna-telegram","selected_chat_id":${selectedId()}}"""
                    )
                }

                path == "/selected" -> {
                    respond(
                        connection.getOutputStream(),
                        200,
                        """{"selected_chat_id":${selectedId()}}"""
                    )
                }

                path.startsWith("/messages") -> {
                    handleMessages(
                        connection,
                        path
                    )
                }

                else -> {
                    respond(
                        connection.getOutputStream(),
                        404,
                        """{"error":"not_found"}"""
                    )
                }
            }
        }
    }

    private fun handleMessages(
        socket: Socket,
        path: String
    ) {
        val id = selectedId()

        if (id == 0L) {
            respond(
                socket.getOutputStream(),
                400,
                """{"error":"no_group_selected"}"""
            )
            return
        }

        val limit =
            Regex("""[?&]limit=(\d+)""")
                .find(path)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.coerceIn(1, 100)
                ?: 50

        val result =
            AtomicReference<TdApi.Object?>(null)

        val latch =
            CountDownLatch(1)

        client.send(
            TdApi.GetChatHistory(
                id,
                0,
                0,
                limit,
                false
            ),
            object : Client.ResultHandler {

                override fun onResult(
                    obj: TdApi.Object
                ) {
                    result.set(obj)
                    latch.countDown()
                }
            }
        )

        if (!latch.await(15, TimeUnit.SECONDS)) {
            respond(
                socket.getOutputStream(),
                504,
                """{"error":"tdlib_timeout"}"""
            )
            return
        }

        when (val obj = result.get()) {

            is TdApi.Messages -> {
                val rows =
                    obj.messages.joinToString(",") { message ->

                        val text =
                            when (
                                val content =
                                    message.content
                            ) {
                                is TdApi.MessageText ->
                                    content.text.text

                                else -> ""
                            }

                        val safeText =
                            escapeJson(text)

                        """{"id":${message.id},"date":${message.date},"text":"$safeText"}"""
                    }

                respond(
                    socket.getOutputStream(),
                    200,
                    """{"chat_id":$id,"messages":[$rows]}"""
                )
            }

            is TdApi.Error -> {
                respond(
                    socket.getOutputStream(),
                    502,
                    """{"error":"tdlib_error","code":${obj.code},"detail":"${escapeJson(obj.message)}"}"""
                )
            }

            else -> {
                val detail =
                    obj?.javaClass?.simpleName
                        ?: "null"

                respond(
                    socket.getOutputStream(),
                    502,
                    """{"error":"tdlib_error","detail":"$detail"}"""
                )
            }
        }
    }

    private fun escapeJson(
        value: String
    ): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        body: String
    ) {
        val status =
            when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                502 -> "Bad Gateway"
                504 -> "Gateway Timeout"
                else -> "Error"
            }

        val bytes =
            body.toByteArray(
                StandardCharsets.UTF_8
            )

        val header =
            "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"

        out.write(
            header.toByteArray(
                StandardCharsets.UTF_8
            )
        )

        out.write(bytes)
        out.flush()
    }
}
