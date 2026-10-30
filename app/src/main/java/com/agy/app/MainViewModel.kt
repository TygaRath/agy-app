package com.agy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface ChatItem {
    val id: String
}

data class UserItem(override val id: String, val text: String) : ChatItem
data class AgentItem(override val id: String, val text: String, val done: Boolean) : ChatItem
data class ToolItem(
    override val id: String,
    val name: String,
    val summary: String,
    val output: String?,
    val done: Boolean,
) : ChatItem

data class ErrorItem(override val id: String, val text: String) : ChatItem

class MainViewModel : ViewModel() {

    private val _conn = MutableStateFlow<ConnState>(ConnState.Checking)
    val conn: StateFlow<ConnState> = _conn

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private var conversationId: String? = null
    private var chatJob: Job? = null
    private var turn = 0
    private var turnHadText = false

    init {
        recheck()
    }

    fun recheck() {
        viewModelScope.launch {
            _conn.value = ConnState.Checking
            delay(400) // keeps the "checking" state visible instead of flashing
            _conn.value = BridgeClient.check()
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        turn++
        turnHadText = false
        add(UserItem("u$turn", prompt))
        _busy.value = true
        chatJob = viewModelScope.launch {
            try {
                BridgeClient.chat(prompt, conversationId).collect { handle(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(ErrorItem("e$turn", "Connection lost: ${e.message ?: "unknown error"}"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun stop() {
        chatJob?.cancel()
    }

    fun newChat() {
        chatJob?.cancel()
        conversationId = null
        _items.value = emptyList()
    }

    // ---- stream-json handling ------------------------------------------------

    private fun handle(ev: JSONObject) {
        when (ev.optString("event")) {
            "init" -> ev.optJSONObject("init")
                ?.optString("conversation_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { conversationId = it }

            "step_update" -> ev.optJSONObject("step_update")?.let(::handleStep)

            "result" -> {
                val r = ev.optJSONObject("result") ?: return
                val status = r.optString("status")
                val response = r.optString("response")
                if (status != "SUCCESS") {
                    add(ErrorItem("e$turn", response.ifBlank { "agy failed ($status)" }))
                } else if (!turnHadText && response.isNotBlank()) {
                    add(AgentItem("a${turn}_final", response, true))
                }
            }
        }
    }

    private fun handleStep(s: JSONObject) {
        val step = s.optInt("step_index", -1)
        val done = s.optString("state") == "DONE"
        when (s.optString("step_type")) {
            "agent_response" -> {
                val delta = s.optString("text_delta", "")
                if (delta.isNotEmpty()) turnHadText = true
                upsertAgent("a${turn}_$step", delta, done)
            }

            "tool" -> {
                val info = s.optJSONObject("tool_info")
                val output = info?.optString("output")?.takeIf { it.isNotBlank() }
                upsertTool(
                    "t${turn}_$step",
                    s.optString("tool_name", "tool"),
                    summarize(info),
                    output,
                    done,
                )
            }
        }
    }

    private fun summarize(info: JSONObject?): String {
        val p = info?.optJSONObject("parameters") ?: return ""
        p.optString("CommandLine").takeIf { it.isNotBlank() }?.let { return it }
        val key = p.keys().asSequence().firstOrNull() ?: return ""
        return p.optString(key).take(120)
    }

    // ---- list helpers ----------------------------------------------------------

    private fun add(item: ChatItem) = _items.update { it + item }

    private fun upsertAgent(id: String, delta: String, done: Boolean) = _items.update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) {
            list + AgentItem(id, delta, done)
        } else {
            val old = list[i] as AgentItem
            list.toMutableList().also { it[i] = old.copy(text = old.text + delta, done = done) }
        }
    }

    private fun upsertTool(
        id: String, name: String, summary: String, output: String?, done: Boolean,
    ) = _items.update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) {
            list + ToolItem(id, name, summary, output, done)
        } else {
            val old = list[i] as ToolItem
            list.toMutableList().also {
                it[i] = old.copy(
                    summary = summary.ifBlank { old.summary },
                    output = output ?: old.output,
                    done = done,
                )
            }
        }
    }
}
