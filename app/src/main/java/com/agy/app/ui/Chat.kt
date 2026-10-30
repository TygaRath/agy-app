package com.agy.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agy.app.AgentItem
import com.agy.app.BridgeStatus
import com.agy.app.ChatItem
import com.agy.app.ErrorItem
import com.agy.app.ToolItem
import com.agy.app.UserItem

@Composable
fun ChatScreen(
    status: BridgeStatus,
    items: List<ChatItem>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(items) {
        if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "New chat", tint = Muted) }
            IconButton(onClick = onRecheck) { Icon(Icons.Default.Refresh, "Recheck", tint = Muted) }
        }
        HorizontalDivider(color = Panel)

        // Messages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ask agy anything", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("It runs in your Termux home folder", color = Muted, fontSize = 13.sp)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    when (item) {
                        is UserItem -> UserBubbleRow(item)
                        is AgentItem -> MarkdownText(item.text, Modifier.fillMaxWidth())
                        is ToolItem -> ToolCard(item)
                        is ErrorItem -> Text(item.text, color = Bad, fontSize = 14.sp)
                    }
                }
                if (busy) {
                    item(key = "busy") {
                        LinearProgressIndicator(
                            modifier = Modifier.width(48.dp).clip(RoundedCornerShape(2.dp)),
                            color = Accent,
                            trackColor = Panel,
                        )
                    }
                }
            }
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

            when {
                busy -> IconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop", tint = Bad) }
                text.isBlank() -> IconButton(onClick = {
                    Toast.makeText(ctx, "Voice: coming next", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.Mic, "Voice", tint = Accent) }
                else -> IconButton(onClick = { onSend(text); text = "" }) {
                    Icon(Icons.Default.Send, "Send", tint = Accent)
                }
            }
        }
    }
}

@Composable
private fun UserBubbleRow(m: UserItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer { Text(m.text, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun ToolCard(t: ToolItem) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t.done) {
                Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
            } else {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, color = Muted, fontSize = 12.sp)
                if (t.summary.isNotBlank()) {
                    Text(
                        t.summary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (t.output != null) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = Muted,
                )
            }
        }
        if (expanded && t.output != null) {
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    t.output.replace("\r\n", "\n").replace("\r", "\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
        }
    }
}
