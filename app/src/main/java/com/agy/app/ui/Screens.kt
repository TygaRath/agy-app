package com.agy.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agy.app.BridgeStatus
import com.agy.app.ChatMessage

// ---- Theme -----------------------------------------------------------------

private val Bg = Color(0xFF0B0D10)
private val Panel = Color(0xFF14181D)
private val Accent = Color(0xFF3DDC97)
private val UserBubble = Color(0xFF1F2A33)
private val Muted = Color(0xFF8A94A0)
private val Warn = Color(0xFFFFB454)
private val Bad = Color(0xFFFF6B6B)

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

// ---- Connection check screens ----------------------------------------------

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

private const val SETUP_CMD = "python agy-bridge.py"

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
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)
        ) {
            SelectionContainer {
                Text(SETUP_CMD, fontFamily = FontFamily.Monospace, color = Accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(SETUP_CMD))
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

// ---- Chat screen (skeleton) --------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    status: BridgeStatus,
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (status.agyInstalled) Accent else Warn))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("agy", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(
                    if (status.agyInstalled) (status.agyVersion ?: "connected") else "agy not found in Termux",
                    color = if (status.agyInstalled) Muted else Warn,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = onRecheck) { Icon(Icons.Default.Refresh, "Recheck", tint = Muted) }
        }
        HorizontalDivider(color = Panel)

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { MessageBubble(it) }
        }

        // Input bar
        var text by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = {
                Toast.makeText(ctx, "Attachments: coming next", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.AttachFile, "Attach", tint = Muted) }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message agy", color = Muted) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Panel,
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel,
                ),
            )

            if (text.isBlank()) {
                IconButton(onClick = {
                    Toast.makeText(ctx, "Voice: coming next", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.Mic, "Voice", tint = Accent) }
            } else {
                IconButton(onClick = { onSend(text); text = "" }) {
                    Icon(Icons.Default.Send, "Send", tint = Accent)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val align = if (m.fromUser) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (m.fromUser) UserBubble else Panel)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(m.text, fontFamily = if (m.fromUser) FontFamily.Default else FontFamily.Monospace, fontSize = 15.sp)
            }
        }
    }
}
