package com.agy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agy.app.ui.AgyTheme
import com.agy.app.ui.ChatScreen
import com.agy.app.ui.CheckingScreen
import com.agy.app.ui.DisconnectedScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val conn by vm.conn.collectAsStateWithLifecycle()
                    val items by vm.items.collectAsStateWithLifecycle()
                    val busy by vm.busy.collectAsStateWithLifecycle()
                    val body = Modifier.systemBarsPadding().imePadding()

                    when (val c = conn) {
                        is ConnState.Checking -> CheckingScreen(body)
                        is ConnState.Disconnected -> DisconnectedScreen(c.reason, vm::recheck, body)
                        is ConnState.Connected -> ChatScreen(
                            status = c.status,
                            items = items,
                            busy = busy,
                            onSend = vm::send,
                            onStop = vm::stop,
                            onNewChat = vm::newChat,
                            onRecheck = vm::recheck,
                            modifier = body,
                        )
                    }
                }
            }
        }
    }
}
