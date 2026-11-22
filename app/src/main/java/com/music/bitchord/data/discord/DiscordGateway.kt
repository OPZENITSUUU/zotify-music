package com.music.bitchord.data.discord

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/**
 * User-authorized Discord Social Layer gateway connection.
 *
 * This is the same client-side model used by Mochy: OAuth authorizes the user,
 * then the app publishes SET_ACTIVITY on that user's Discord session. It does
 * not change the shared Zotify bot account.
 */
internal class DiscordGateway(private val accessToken: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val client = HttpClient(OkHttp) { install(WebSockets) }

    @Volatile private var socket: DefaultClientWebSocketSession? = null
    @Volatile private var reader: Job? = null
    @Volatile private var heartbeat: Job? = null
    @Volatile private var sequence: Long? = null
    @Volatile private var heartbeatAcked = true
    @Volatile private var heartbeatInterval = 0L
    @Volatile private var ready = false

    suspend fun connect() = connectLock.withLock {
        if (ready && socket?.isActive == true) return@withLock
        disconnectSocket()
        sequence = null
        val readySignal = CompletableDeferred<Unit>()
        val session = client.webSocketSession(GATEWAY_URL) {
            header("User-Agent", "Discord-Android/314013;RNA")
            header("Accept-Language", "en-US")
            header("Cache-Control", "no-cache")
            header("Pragma", "no-cache")
        }
        socket = session
        reader = scope.launch {
            try {
                session.incoming.receiveAsFlow().collect { frame ->
                    if (frame is Frame.Text) handleMessage(frame.readText(), readySignal)
                }
                throw IOException("Discord gateway closed the connection")
            } catch (failure: Exception) {
                ready = false
                readySignal.completeExceptionally(failure)
            } finally {
                ready = false
                heartbeat?.cancel()
                heartbeat = null
            }
        }
        try {
            withTimeout(READY_TIMEOUT_MS) { readySignal.await() }
        } catch (failure: Exception) {
            disconnectSocket()
            throw failure
        }
    }

    suspend fun setActivity(activity: JSONObject?, status: String = "online") {
        if (!ready || socket?.isActive != true) connect()
        val data = JSONObject()
            .put("since", JSONObject.NULL)
            .put("activities", if (activity == null) org.json.JSONArray() else org.json.JSONArray().put(activity))
            .put("status", status)
            .put("afk", false)
        send(JSONObject().put("op", OP_PRESENCE_UPDATE).put("d", data))
    }

    private suspend fun handleMessage(raw: String, readySignal: CompletableDeferred<Unit>) {
        val payload = JSONObject(raw)
        if (!payload.isNull("s")) sequence = payload.optLong("s")
        when (payload.optInt("op", -1)) {
            OP_HELLO -> {
                heartbeatInterval = payload.optJSONObject("d")?.optLong("heartbeat_interval")
                    ?.takeIf { it > 0 } ?: 41_250L
                sendIdentify()
                startHeartbeat()
            }
            OP_HEARTBEAT -> sendHeartbeat()
            OP_HEARTBEAT_ACK -> heartbeatAcked = true
            OP_DISPATCH -> when (payload.optString("t")) {
                "READY", "RESUMED" -> {
                    ready = true
                    readySignal.complete(Unit)
                }
            }
            OP_RECONNECT, OP_INVALID_SESSION -> {
                ready = false
                readySignal.completeExceptionally(IOException("Discord gateway requested re-identification"))
                socket?.close()
            }
        }
    }

    private suspend fun sendIdentify() {
        val properties = JSONObject()
            .put("os", "Android")
            .put("browser", "Zotify Music")
            .put("device", "Android")
        val data = JSONObject()
            .put("token", "Bearer $accessToken")
            .put("capabilities", CAPABILITIES)
            .put("intents", INTENTS)
            .put("properties", properties)
        send(JSONObject().put("op", OP_IDENTIFY).put("d", data))
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeatAcked = true
        heartbeat = scope.launch {
            delay((heartbeatInterval * Random.nextDouble()).toLong())
            while (isActive && socket?.isActive == true) {
                if (!heartbeatAcked) {
                    ready = false
                    socket?.close()
                    return@launch
                }
                heartbeatAcked = false
                sendHeartbeat()
                delay(heartbeatInterval)
            }
        }
    }

    private suspend fun sendHeartbeat() {
        send(JSONObject().put("op", OP_HEARTBEAT).put("d", sequence ?: JSONObject.NULL))
    }

    private suspend fun send(payload: JSONObject) {
        val active = socket?.takeIf { it.isActive } ?: throw IOException("Discord gateway is disconnected")
        active.send(Frame.Text(payload.toString()))
    }

    private suspend fun disconnectSocket() {
        ready = false
        heartbeat?.cancel()
        heartbeat = null
        reader?.cancel()
        reader = null
        val old = socket
        socket = null
        runCatching { old?.close() }
    }

    suspend fun close() {
        disconnectSocket()
        client.close()
        scope.cancel()
    }

    companion object {
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
        private const val READY_TIMEOUT_MS = 20_000L
        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_PRESENCE_UPDATE = 3
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private const val CAPABILITIES = (1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16)
        private const val INTENTS = (1 shl 12) or (1 shl 18) or (1 shl 19) or
            (1 shl 22) or (1 shl 27) or (1 shl 28) or (1 shl 29)
    }
}
