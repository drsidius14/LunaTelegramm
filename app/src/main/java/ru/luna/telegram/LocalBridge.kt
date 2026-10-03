package ru.luna.telegram

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalBridge(
    private val port: Int,
    val token: String,
    private val selectedId: () -> Long
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        pool.submit {
            try {
                server = ServerSocket(port)
                while (!server!!.isClosed) {
                    val socket = server!!.accept()
                    pool.submit { handle(socket) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val request = reader.readLine() ?: return
            val headers = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                headers += line
            }

            val auth = headers.firstOrNull { it.startsWith("Authorization:", true) }
            if (auth?.substringAfter(":")?.trim() != "Bearer $token") {
                respond(it.getOutputStream(), 401, """{"error":"unauthorized"}""")
                return
            }

            val path = request.split(" ").getOrNull(1) ?: "/"
            when {
                path == "/health" ->
                    respond(it.getOutputStream(), 200,
                        """{"ok":true,"service":"luna-telegram","selected_chat_id":${selectedId()}}""")

                path == "/selected" ->
                    respond(it.getOutputStream(), 200,
                        """{"selected_chat_id":${selectedId()}}""")

                path.startsWith("/messages") -> {
                    val id = selectedId()
                    if (id == 0L) {
                        respond(it.getOutputStream(), 400, """{"error":"no_group_selected"}""")
                        return
                    }

                    val limit = Regex("""limit=(\d+)""").find(path)
                        ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 50

                    val result = arrayOfNulls<TdApi.TLObject>(1)
                    val latch = java.util.concurrent.CountDownLatch(1)

                    Client.send(
                        TdApi.GetChatHistory(id, 0, 0, limit, false),
                        object : Client.ResultHandler {
                            override fun onResult(obj: TdApi.TLObject) {
                                result[0] = obj
                                latch.countDown()
                            }
                        }
                    )

                    if (!latch.await(15, TimeUnit.SECONDS)) {
                        respond(it.getOutputStream(), 504, """{"error":"tdlib_timeout"}""")
                        return
                    }

                    val obj = result[0]
                    if (obj is TdApi.Messages) {
                        val rows = obj.messages.joinToString(",") { m ->
                            val text = when (val c = m.content) {
                                is TdApi.MessageText -> c.text.text
                                else -> ""
                            }.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                            """{"id":${m.id},"date":${m.date},"text":"$text"}"""
                        }
                        respond(it.getOutputStream(), 200,
                            """{"chat_id":$id,"messages":[$rows]}""")
                    } else {
                        respond(it.getOutputStream(), 502,
                            """{"error":"tdlib_error","detail":"${obj?.javaClass?.simpleName ?: "null"}"}""")
                    }
                }

                else -> respond(it.getOutputStream(), 404, """{"error":"not_found"}""")
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 502 -> "Bad Gateway"; 504 -> "Gateway Timeout"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header =
            "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
