package com.agy.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String)

class MainViewModel : ViewModel() {

    private val _conn = MutableStateFlow<ConnState>(ConnState.Checking)
    val conn: StateFlow<ConnState> = _conn

    private val _messages = MutableStateFlow(
        listOf(ChatMessage(false, "Connected to the Termux bridge. The live agy session isn't wired up yet — that's the next step."))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

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
        if (text.isBlank()) return
        _messages.value = _messages.value + ChatMessage(true, text.trim())
        // TODO: forward to the agy session over the bridge WebSocket
    }
}
