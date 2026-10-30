package com.agy.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface ChatItem {
    val id: String
}

data class UserItem(override val id: String, val text: String, val images: List<String>) : ChatItem
data class AgentItem(override val id: String, val text: String, val done: Boolean) : ChatItem
data class ToolItem(
    override val id: String,
    val name: String,
    val summary: String,
    val output: String?,
    val done: Boolean,
    val rule: String? = null,
    val broadRule: String? = null,
) : ChatItem

data class ErrorItem(override val id: String, val text: String) : ChatItem

enum class ApprovalState { PENDING, ALLOWED, DENIED }

/** One blocked action: what to show, the exact allow rule, and a broader rule for "always allow". */
data class PendingAction(val label: String, val rule: String, val broadRule: String?)

data class ApprovalItem(
    override val id: String,
    val actions: List<PendingAction>,
    val state: ApprovalState,
    val details: String = "",
) : ChatItem

private val WRITE_TOOLS = setOf(
    "write_file", "write_to_file", "replace_file_content", "multi_replace_file_content", "sed_file",
)
private val PATH_KEYS = listOf("Path", "TargetFile", "FilePath", "AbsolutePath", "path", "File")
private const val RETRY_PROMPT =
    "Permission has now been granted for the action(s) you were just blocked on. Please go ahead and retry them."

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("agy", Context.MODE_PRIVATE)

    // ---- settings (persisted) ---------------------------------------------------
    private val _token = MutableStateFlow(prefs.getString("token", "") ?: "")
    val token: StateFlow<String> = _token

    private val _cwd = MutableStateFlow(prefs.getString("cwd", "") ?: "")
    val cwd: StateFlow<String> = _cwd

    private val _model = MutableStateFlow(prefs.getString("model", "") ?: "")
    val model: StateFlow<String> = _model

    private val _effort = MutableStateFlow(prefs.getString("effort", "") ?: "")
    val effort: StateFlow<String> = _effort

    private val _agent = MutableStateFlow(prefs.getString("agent", "") ?: "")
    val agent: StateFlow<String> = _agent

    /** "" = ask (agy default), or accept-edits / auto / plan */
    private val _mode = MutableStateFlow(prefs.getString("mode", "accept-edits") ?: "accept-edits")
    val mode: StateFlow<String> = _mode

    /** Rules the user chose to "always allow"; sent with every turn. */
    private val _allowRules = MutableStateFlow(prefs.getStringSet("allow", emptySet())?.toSet() ?: emptySet())
    val allowRules: StateFlow<Set<String>> = _allowRules

    fun setMode(m: String) {
        _mode.value = m
        prefs.edit().putString("mode", m).apply()
    }

    fun saveSettings(cwd: String, model: String, effort: String, agent: String) {
        _cwd.value = cwd
        _model.value = model
        _effort.value = effort
        _agent.value = agent
        prefs.edit()
            .putString("cwd", cwd).putString("model", model)
            .putString("effort", effort).putString("agent", agent)
            .apply()
        loadCommands()
    }

    fun clearAllowRules() {
        _allowRules.value = emptySet()
        prefs.edit().remove("allow").apply()
    }

    fun saveToken(t: String) {
        val clean = t.trim()
        _token.value = clean
        prefs.edit().putString("token", clean).apply()
        recheck()
    }

    fun clearToken() = saveToken("")

    // ---- connection ---------------------------------------------------------------
    private val _conn = MutableStateFlow<ConnState>(ConnState.Checking)
    val conn: StateFlow<ConnState> = _conn

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands

    private var conversationId: String? = null
    private var chatJob: Job? = null
    private var turn = 0
    private var turnHadText = false
    private var gotResult = false

    init {
        recheck()
    }

    fun recheck() {
        viewModelScope.launch {
            _conn.value = ConnState.Checking
            delay(400) // keeps the "checking" state visible instead of flashing
            val c = BridgeClient.check()
            _conn.value = if (c is ConnState.Connected) {
                when (BridgeClient.authCode(_token.value)) {
                    200 -> {
                        loadCommands()
                        c
                    }
                    401 -> ConnState.NeedsToken(c.status, _token.value.isNotEmpty())
                    404 -> ConnState.Disconnected(
                        "Your bridge is outdated. Replace agy-bridge.py with the new version and restart it."
                    )
                    else -> ConnState.Disconnected("Couldn't verify the bridge. Try again.")
                }
            } else {
                c
            }
        }
    }

    private fun loadCommands() {
        viewModelScope.launch {
            _commands.value = BridgeClient.commands(_token.value, _cwd.value)
        }
    }

    /** Read-only agy info for the Tools screen: plugins | agents | mcp | models. */
    suspend fun cli(name: String): String = BridgeClient.cli(_token.value, name)

    // ---- chatting -----------------------------------------------------------------

    fun send(text: String, uris: List<Uri>) {
        val prompt = text.trim()
        if (prompt.isEmpty() && uris.isEmpty()) return
        runTurn(prompt, uris, showUser = true, extraAllow = emptyList())
    }

    private fun runTurn(prompt: String, uris: List<Uri>, showUser: Boolean, extraAllow: List<String>) {
        if (_busy.value) return
        turn++
        turnHadText = false
        gotResult = false
        if (showUser) add(UserItem("u$turn", prompt, uris.map { it.toString() }))
        _busy.value = true
        chatJob = viewModelScope.launch {
            try {
                val paths = uris.map { uploadUri(it) }
                val req = ChatRequest(
                    prompt = prompt.ifEmpty { "Please look at the attached image(s)." },
                    conversationId = conversationId,
                    mode = _mode.value,
                    cwd = _cwd.value,
                    model = _model.value,
                    effort = _effort.value,
                    images = paths,
                    agent = _agent.value,
                    allow = (_allowRules.value + extraAllow).toList(),
                )
                BridgeClient.chat(_token.value, req).collect { handle(it) }
                if (!gotResult) {
                    add(ErrorItem("e$turn", "The stream ended before agy finished. Check the bridge session in Termux for errors."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(ErrorItem("e$turn", e.message ?: "Connection lost"))
            } finally {
                finishOpenItems()
                _busy.value = false
            }
        }
    }

    private suspend fun uploadUri(uri: Uri): String {
        val cr = getApplication<Application>().contentResolver
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri)) ?: "jpg"
        val bytes = withContext(Dispatchers.IO) {
            cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the image")
        }
        return BridgeClient.upload(_token.value, "img.$ext", bytes)
    }

    fun stop() {
        chatJob?.cancel()
    }

    fun newChat() {
        chatJob?.cancel()
        conversationId = null
        _items.value = emptyList()
    }

    // ---- approvals ---------------------------------------------------------------

    /** Allow the blocked actions and ask agy to retry. [always] also remembers a broader rule. */
    fun approve(id: String, always: Boolean) {
        val item = _items.value.firstOrNull { it.id == id } as? ApprovalItem ?: return
        if (item.state != ApprovalState.PENDING || _busy.value) return
        setApproval(id, ApprovalState.ALLOWED)
        if (always) {
            val broad = item.actions.map { it.broadRule ?: it.rule }
            val updated = _allowRules.value + broad
            _allowRules.value = updated
            prefs.edit().putStringSet("allow", updated).apply()
            runTurn(RETRY_PROMPT, emptyList(), showUser = false, extraAllow = emptyList())
        } else {
            runTurn(RETRY_PROMPT, emptyList(), showUser = false, extraAllow = item.actions.map { it.rule })
        }
    }

    fun deny(id: String) = setApproval(id, ApprovalState.DENIED)

    private fun setApproval(id: String, state: ApprovalState) = _items.update { list ->
        list.map { if (it is ApprovalItem && it.id == id) it.copy(state = state) else it }
    }

    // ---- stream-json handling ------------------------------------------------

    private fun handle(ev: JSONObject) {
        when (ev.optString("event")) {
            "init" -> ev.optJSONObject("init")
                ?.optString("conversation_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { conversationId = it }

            "step_update" -> ev.optJSONObject("step_update")?.let(::handleStep)

            "bridge_stderr" -> {
                val arr = ev.optJSONArray("lines") ?: return
                val text = (0 until arr.length()).joinToString("\n") { arr.optString(it) }
                _items.update { list ->
                    list.map { if (it is ApprovalItem && it.id == "p$turn") it.copy(details = text) else it }
                }
            }

            "result" -> {
                val r = ev.optJSONObject("result") ?: return
                gotResult = true
                val status = r.optString("status")
                val response = r.optString("response")
                if (status != "SUCCESS") {
                    add(ErrorItem("e$turn", response.ifBlank { "agy failed ($status)" }))
                } else if (!turnHadText && response.isNotBlank()) {
                    add(AgentItem("a${turn}_final", response, true))
                }
                r.optJSONArray("denied_actions")?.let { handleDenied(it) }
            }
        }
    }

    /** agy soft-denies anything needing approval in print mode; turn those into approval cards. */
    private fun handleDenied(denied: org.json.JSONArray) {
        if (denied.length() == 0) return
        var cmdCount = 0
        var otherCount = 0
        for (i in 0 until denied.length()) {
            if (denied.optJSONObject(i)?.optString("action") == "command") cmdCount++ else otherCount++
        }
        val turnTools = _items.value.filterIsInstance<ToolItem>()
            .filter { it.id.startsWith("t${turn}_") && it.rule != null }
        val cmds = turnTools.filter { it.name == "run_command" && it.output == null }.takeLast(cmdCount)
        val writes = turnTools.filter { it.name != "run_command" }.takeLast(otherCount)
        val pending = (cmds + writes).map { PendingAction(it.summary, it.rule!!, it.broadRule) }
        if (pending.isNotEmpty()) {
            add(ApprovalItem("p$turn", pending, ApprovalState.PENDING))
        } else {
            add(ErrorItem("e${turn}_denied", "agy blocked ${denied.length()} action(s) that need approval, but I couldn't tell which."))
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
                val name = s.optString("tool_name", "tool")
                val output = info?.optString("output")?.takeIf { it.isNotBlank() }
                val (rule, broad) = ruleFor(name, info)
                upsertTool("t${turn}_$step", name, summarize(info), output, done, rule, broad)
            }
        }
    }

    private fun summarize(info: JSONObject?): String {
        val p = info?.optJSONObject("parameters") ?: return ""
        p.optString("CommandLine").takeIf { it.isNotBlank() }?.let { return it }
        val key = p.keys().asSequence().firstOrNull() ?: return ""
        return p.optString(key).take(120)
    }

    /** Permission rule (exact, and broader) that would allow this tool call. */
    private fun ruleFor(name: String, info: JSONObject?): Pair<String?, String?> {
        val p = info?.optJSONObject("parameters") ?: return null to null
        if (name == "run_command") {
            val cmd = p.optString("CommandLine").trim()
            if (cmd.isEmpty() || '\n' in cmd) return null to null
            return "command($cmd)" to "command(${cmd.split(" ").first()})"
        }
        if (name in WRITE_TOOLS) {
            val path = PATH_KEYS.firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } }
                ?: return null to null
            if ('\n' in path) return null to null
            val dir = path.substringBeforeLast('/', "")
            return "write_file($path)" to (if (dir.isNotEmpty()) "write_file($dir/)" else null)
        }
        return null to null
    }

    // ---- list helpers ----------------------------------------------------------

    private fun add(item: ChatItem) = _items.update { it + item }

    /** Stops spinners on anything still marked in-progress once a turn ends or is cancelled. */
    private fun finishOpenItems() = _items.update { list ->
        list.map {
            when {
                it is ToolItem && !it.done -> it.copy(done = true)
                it is AgentItem && !it.done -> it.copy(done = true)
                else -> it
            }
        }
    }

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
        rule: String?, broadRule: String?,
    ) = _items.update { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) {
            list + ToolItem(id, name, summary, output, done, rule, broadRule)
        } else {
            val old = list[i] as ToolItem
            list.toMutableList().also {
                it[i] = old.copy(
                    summary = summary.ifBlank { old.summary },
                    output = output ?: old.output,
                    done = done,
                    rule = rule ?: old.rule,
                    broadRule = broadRule ?: old.broadRule,
                )
            }
        }
    }
}
