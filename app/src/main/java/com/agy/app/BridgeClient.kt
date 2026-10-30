package com.agy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BridgeStatus(
    val bridgeVersion: String,
    val agyInstalled: Boolean,
    val agyPath: String?,
    val agyVersion: String?,
)

sealed interface ConnState {
    data object Checking : ConnState
    data class Connected(val status: BridgeStatus) : ConnState
    data class Disconnected(val reason: String) : ConnState
}

object BridgeClient {
    const val HOST = "127.0.0.1"
    const val PORT = 8765
    private const val BASE = "http://$HOST:$PORT"

    /** Asks the Termux bridge whether it is alive and whether `agy` exists. */
    suspend fun check(): ConnState = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            ConnState.Connected(
                BridgeStatus(
                    bridgeVersion = j.optString("bridge_version", "?"),
                    agyInstalled = j.optBoolean("agy_installed", false),
                    agyPath = j.optString("agy_path").takeIf { it.isNotBlank() && it != "null" },
                    agyVersion = j.optString("agy_version").takeIf { it.isNotBlank() && it != "null" },
                )
            )
        } catch (e: java.net.ConnectException) {
            ConnState.Disconnected("Bridge isn't running in Termux.")
        } catch (e: java.net.SocketTimeoutException) {
            ConnState.Disconnected("Bridge didn't respond in time.")
        } catch (e: Exception) {
            ConnState.Disconnected(e.message ?: "Unknown connection error.")
        }
    }

    /**
     * Sends one prompt and emits agy's stream-json events (one JSONObject per line)
     * as they arrive. Cancelling the collector disconnects, which makes the bridge
     * kill the agy process.
     */
    fun chat(prompt: String, conversationId: String?): Flow<JSONObject> = callbackFlow {
        val conn = URL("$BASE/chat").openConnection() as HttpURLConnection
        val reader = launch(Dispatchers.IO) {
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 0 // agy turns can take minutes
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("prompt", prompt).apply {
                    if (!conversationId.isNullOrBlank()) put("conversation_id", conversationId)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }

                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("{")) {
                            try {
                                trySend(JSONObject(line))
                            } catch (_: Exception) {
                                // ignore a malformed line
                            }
                        }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose {
            conn.disconnect()
            reader.cancel()
        }
    }
}
