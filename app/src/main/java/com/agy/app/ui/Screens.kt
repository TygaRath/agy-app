package com.agy.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Theme (shared across ui files) -------------------------------------------

internal val Bg = Color(0xFF0B0D10)
internal val Panel = Color(0xFF14181D)
internal val Accent = Color(0xFF3DDC97)
internal val UserBubble = Color(0xFF1F2A33)
internal val Muted = Color(0xFF8A94A0)
internal val Warn = Color(0xFFFFB454)
internal val Bad = Color(0xFFFF6B6B)

@Composable
fun AgyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Bg,
            surface = Bg,
            onSurface = Color(0xFFE6EAEE),
            onBackground = Color(0xFFE6EAEE),
        ),
        content = content,
    )
}

// ---- Connection check screens ------------------------------------------------------

@Composable
fun CheckingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(20.dp))
        Text("Checking Termux bridge…", color = Muted)
    }
}

private const val SETUP_CMD = "cd ~/agy-app/bridge && python agy-bridge.py"

@Composable
fun DisconnectedScreen(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Can't reach Termux", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(reason, color = Bad)
        Spacer(Modifier.height(24.dp))
        Text("To fix, open Termux and run:", color = Muted)
        Spacer(Modifier.height(8.dp))
        CodeBox(SETUP_CMD)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(SETUP_CMD))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy command") }
        Text(
            "Keep Termux running in the background, then come back and retry.",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun CodeBox(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, color = Accent, fontSize = 13.sp)
        }
    }
}

// ---- Pairing ---------------------------------------------------------------------

@Composable
fun TokenScreen(wrong: Boolean, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pair with Termux", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The bridge uses a private token so other apps on your phone can't control agy. " +
                "It's printed when the bridge starts. To show it again, run this in Termux:",
            color = Muted,
        )
        Spacer(Modifier.height(12.dp))
        CodeBox("cat ~/.agy-bridge-token")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Pairing token") },
        )
        if (wrong) {
            Spacer(Modifier.height(6.dp))
            Text("That token was rejected. Check it and try again.", color = Bad, fontSize = 13.sp)
        }
        TextButton(onClick = { clipboard.getText()?.text?.let { token = it.trim() } }) {
            Text("Paste from clipboard")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(token) },
            enabled = token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect") }
    }
}

// ---- Settings --------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    cwd: String,
    model: String,
    effort: String,
    agent: String,
    allowRuleCount: Int,
    onSave: (cwd: String, model: String, effort: String, agent: String) -> Unit,
    onClearRules: () -> Unit,
    onRecheck: () -> Unit,
    onResetToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    var c by remember { mutableStateOf(cwd) }
    var m by remember { mutableStateOf(model) }
    var e by remember { mutableStateOf(effort) }
    var a by remember { mutableStateOf(agent) }
    val efforts = listOf("" to "Default", "low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "XHigh", "max" to "Max")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = { onSave(c.trim(), m.trim(), e, a.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = c, onValueChange = { c = it }, singleLine = true,
                    label = { Text("Working folder") }, placeholder = { Text("~ (home)") },
                )
                OutlinedTextField(
                    value = m, onValueChange = { m = it }, singleLine = true,
                    label = { Text("Model (optional)") },
                )
                OutlinedTextField(
                    value = a, onValueChange = { a = it }, singleLine = true,
                    label = { Text("Agent (optional)") },
                )
                Text("Reasoning effort", color = Muted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((value, label) in efforts) {
                        FilterChip(selected = e == value, onClick = { e = value }, label = { Text(label) })
                    }
                }
                if (allowRuleCount > 0) {
                    TextButton(onClick = onClearRules) { Text("Clear $allowRuleCount always-allowed rule(s)") }
                }
                TextButton(onClick = onRecheck) { Text("Recheck connection") }
                TextButton(onClick = onResetToken) { Text("Change pairing token") }
            }
        },
    )
}

// ---- Tools: plugins / agents / MCP / models ----------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsDialog(load: suspend (String) -> String, onDismiss: () -> Unit) {
    val tabs = listOf("plugins" to "Plugins", "agents" to "Agents", "mcp" to "MCP", "models" to "Models")
    var tab by remember { mutableStateOf("plugins") }
    var output by remember { mutableStateOf("Loading…") }
    LaunchedEffect(tab) {
        output = "Loading…"
        output = load(tab)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("agy tools") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((value, label) in tabs) {
                        FilterChip(selected = tab == value, onClick = { tab = value }, label = { Text(label) })
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(output, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        },
    )
}
