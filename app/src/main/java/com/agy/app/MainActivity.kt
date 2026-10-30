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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agy.app.ui.AgyTheme
import com.agy.app.ui.ChatScreen
import com.agy.app.ui.CheckingScreen
import com.agy.app.ui.DisconnectedScreen
import com.agy.app.ui.LocalBridgeToken
import com.agy.app.ui.SettingsDialog
import com.agy.app.ui.TokenScreen
import com.agy.app.ui.ToolsDialog

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
                    val mode by vm.mode.collectAsStateWithLifecycle()
                    val token by vm.token.collectAsStateWithLifecycle()
                    val cwd by vm.cwd.collectAsStateWithLifecycle()
                    val model by vm.model.collectAsStateWithLifecycle()
                    val effort by vm.effort.collectAsStateWithLifecycle()
                    val agent by vm.agent.collectAsStateWithLifecycle()
                    val allowRules by vm.allowRules.collectAsStateWithLifecycle()
                    val commands by vm.commands.collectAsStateWithLifecycle()
                    var showSettings by remember { mutableStateOf(false) }
                    var showTools by remember { mutableStateOf(false) }
                    val body = Modifier.systemBarsPadding().imePadding()

                    CompositionLocalProvider(LocalBridgeToken provides token) {
                        when (val c = conn) {
                            is ConnState.Checking -> CheckingScreen(body)
                            is ConnState.Disconnected -> DisconnectedScreen(c.reason, vm::recheck, body)
                            is ConnState.NeedsToken -> TokenScreen(c.wrong, vm::saveToken, body)
                            is ConnState.Connected -> ChatScreen(
                                status = c.status,
                                items = items,
                                busy = busy,
                                mode = mode,
                                commands = commands,
                                onMode = vm::setMode,
                                onSend = vm::send,
                                onStop = vm::stop,
                                onNewChat = vm::newChat,
                                onOpenSettings = { showSettings = true },
                                onOpenTools = { showTools = true },
                                onApprove = vm::approve,
                                onDeny = vm::deny,
                                modifier = body,
                            )
                        }

                        if (showSettings) {
                            SettingsDialog(
                                cwd = cwd,
                                model = model,
                                effort = effort,
                                agent = agent,
                                allowRuleCount = allowRules.size,
                                onSave = { c, m, e, a ->
                                    vm.saveSettings(c, m, e, a)
                                    showSettings = false
                                },
                                onClearRules = vm::clearAllowRules,
                                onRecheck = {
                                    vm.recheck()
                                    showSettings = false
                                },
                                onResetToken = {
                                    vm.clearToken()
                                    showSettings = false
                                },
                                onDismiss = { showSettings = false },
                            )
                        }
                        if (showTools) {
                            ToolsDialog(load = vm::cli, onDismiss = { showTools = false })
                        }
                    }
                }
            }
        }
    }
}
