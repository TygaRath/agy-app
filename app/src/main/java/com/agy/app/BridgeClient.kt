package com.agy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BridgeStatus(
    val bridgeVersion: String,
    val agyInstalled: Boolean,
    val agyPath: String?,
    val agyVersion: String?,
)

sealed interface ConnState {
    data object Checking : ConnState
    data class Connected(val status: BridgeStatus) : ConnState
    data class NeedsToken(val status: BridgeStatus, val wrong: Boolean) : ConnState
    data class Disconnected(val reason: String) : ConnState
}

data class ChatRequest(
    val prompt: String,
    val conversationId: String?,
    val mode: String?,
    val cwd: String?,
    val model: String?,
    val effort: String?,
    val images: List<String>,
    val agent: String? = null,
    val allow: List<String> = emptyList(),
)

data class SlashCommand(val name: String, val description: String, val source: String)

object BridgeClient {
    const val HOST = "127.0.0.1"
    const val PORT = 8765
    private const val BASE = "http://$HOST:$PORT"
    private const val TOKEN_HEADER = "X-Agy-Token"

    /** URL the bridge serves an image file from (used by the image loader). */
    fun fileUrl(path: String, token: String): String =
        "$BASE/file?path=${URLEncoder.encode(path, "UTF-8")}&t=${URLEncoder.encode(token, "UTF-8")}"

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

    /** HTTP status of the token check: 200 ok, 401 bad token, 404 old bridge, -1 error. */
    suspend fun authCode(token: String): Int = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE/auth").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 2500
            conn.setRequestProperty(TOKEN_HEADER, token)
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }

    /** Skills that can be run as /commands. Empty list on any failure. */
    suspend fun commands(token: String, cwd: String): List<SlashCommand> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/commands?cwd=${URLEncoder.encode(cwd, "UTF-8")}"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 5000
            conn.setRequestProperty(TOKEN_HEADER, token)
            if (conn.responseCode != 200) return@withContext emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("commands")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SlashCommand(o.optString("name"), o.optString("description"), o.optString("source"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Read-only agy info: plugins | agents | mcp | models. */
    suspend fun cli(token: String, name: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$BASE/cli?c=${URLEncoder.encode(name, "UTF-8")}").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 30000
            conn.setRequestProperty(TOKEN_HEADER, token)
            if (conn.responseCode != 200) return@withContext "Request failed (HTTP ${conn.responseCode})"
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("output")
        } catch (e: Exception) {
            "Couldn't load: ${e.message}"
        }
    }

    /** Uploads image bytes to the bridge; returns the file path inside Termux. */
    suspend fun upload(token: String, name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val conn = URL("$BASE/upload?name=${URLEncoder.encode(name, "UTF-8")}").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 60000
            conn.setRequestProperty(TOKEN_HEADER, token)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode != 200) error("Upload failed (HTTP ${conn.responseCode})")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("path")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Sends one prompt and emits agy's stream-json events (one JSONObject per line)
     * as they arrive. Cancelling the collector disconnects, which makes the bridge
     * kill the agy process.
     */
    fun chat(token: String, req: ChatRequest): Flow<JSONObject> = callbackFlow {
        val conn = URL("$BASE/chat").openConnection() as HttpURLConnection
        val reader = launch(Dispatchers.IO) {
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 0 // agy turns can take minutes
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty(TOKEN_HEADER, token)
                val body = JSONObject().put("prompt", req.prompt).apply {
                    if (!req.conversationId.isNullOrBlank()) put("conversation_id", req.conversationId)
                    if (!req.mode.isNullOrBlank()) put("mode", req.mode)
                    if (!req.cwd.isNullOrBlank()) put("cwd", req.cwd)
                    if (!req.model.isNullOrBlank()) put("model", req.model)
                    if (!req.effort.isNullOrBlank()) put("effort", req.effort)
                    if (req.images.isNotEmpty()) put("images", JSONArray(req.images))
                    if (!req.agent.isNullOrBlank()) put("agent", req.agent)
                    if (req.allow.isNotEmpty()) put("allow", JSONArray(req.allow))
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                if (code != 200) {
                    close(IllegalStateException(if (code == 401) "The pairing token was rejected." else "Bridge returned HTTP $code"))
                    return@launch
                }

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
