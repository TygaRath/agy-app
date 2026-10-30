package com.agy.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.agy.app.AgentItem
import com.agy.app.ApprovalItem
import com.agy.app.ApprovalState
import com.agy.app.BridgeStatus
import com.agy.app.ChatItem
import com.agy.app.ErrorItem
import com.agy.app.SlashCommand
import com.agy.app.ToolItem
import com.agy.app.UserItem

private data class PaletteEntry(val name: String, val description: String, val native: Boolean)

private val SUGGESTIONS = listOf(
    "What's in this folder?",
    "Summarize this project",
    "Show git status",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    status: BridgeStatus,
    items: List<ChatItem>,
    busy: Boolean,
    mode: String,
    commands: List<SlashCommand>,
    onMode: (String) -> Unit,
    onSend: (String, List<Uri>) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit,
    onApprove: (String, Boolean) -> Unit,
    onDeny: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(items) {
        if (items.isNotEmpty()) listState.scrollToItem(items.lastIndex)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        pending = (pending + uris).distinct().take(6)
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val spoken = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            text = (text + " " + spoken).trim()
        }
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
            val modes = listOf(
                "" to "Ask",
                "accept-edits" to "Accept edits",
                "auto" to "Auto",
                "plan" to "Plan",
            )
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(modes.firstOrNull { it.first == mode }?.second ?: "Ask", color = Accent, fontSize = 13.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for ((value, label) in modes) {
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onMode(value); menuOpen = false },
                        )
                    }
                }
            }
            IconButton(onClick = onNewChat) { Icon(Icons.Default.Add, "New chat", tint = Muted) }
            IconButton(onClick = onOpenTools) { Icon(Icons.Default.Build, "Tools", tint = Muted) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = Muted) }
        }
        HorizontalDivider(color = Panel)

        // Messages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ask agy anything", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("Type / for commands and skills", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(18.dp))
                    for (s in SUGGESTIONS) {
                        SuggestionChip(onClick = { onSend(s, emptyList()) }, label = { Text(s) })
                    }
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
                        is ApprovalItem -> ApprovalCard(
                            item, busy,
                            onApprove = { always -> onApprove(item.id, always) },
                            onDeny = { onDeny(item.id) },
                        )
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

        // Slash-command palette
        val query = if (text.startsWith("/") && ' ' !in text) text.drop(1).lowercase() else null
        if (query != null) {
            val entries = listOf(
                PaletteEntry("new", "Start a new chat", true),
                PaletteEntry("settings", "Open settings", true),
                PaletteEntry("tools", "Plugins, agents, MCP, models", true),
            ) + commands.map { PaletteEntry(it.name, it.description, false) }
            val matches = entries.filter { it.name.lowercase().contains(query) }.take(6)
            if (matches.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Panel)
                ) {
                    for (e in matches) {
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                if (e.native) {
                                    text = ""
                                    when (e.name) {
                                        "new" -> onNewChat()
                                        "settings" -> onOpenSettings()
                                        "tools" -> onOpenTools()
                                    }
                                } else {
                                    text = "/${e.name} "
                                }
                            }.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("/${e.name}", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            if (e.description.isNotBlank()) {
                                Text(e.description, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Pending image attachments
        if (pending.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (u in pending) {
                    Box {
                        AsyncImage(
                            model = u,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        IconButton(
                            onClick = { pending = pending - u },
                            modifier = Modifier.size(22.dp).align(Alignment.TopEnd)
                                .background(Color(0x99000000), CircleShape),
                        ) {
                            Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }

        // Input bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = { picker.launch("image/*") }) {
                Icon(Icons.Default.AttachFile, "Attach images", tint = Muted)
            }

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
                text.isBlank() && pending.isEmpty() -> IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to agy")
                    }
                    try {
                        speech.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "No speech recognition service found", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Default.Mic, "Voice", tint = Accent) }
                else -> IconButton(onClick = {
                    onSend(text, pending)
                    text = ""
                    pending = emptyList()
                }) { Icon(Icons.Default.Send, "Send", tint = Accent) }
            }
        }
    }
}

@Composable
private fun UserBubbleRow(m: UserItem) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (m.images.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (img in m.images) ImageThumb(Uri.parse(img), Modifier.size(120.dp))
            }
        }
        if (m.text.isNotBlank()) {
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
}

@Composable
private fun ApprovalCard(
    a: ApprovalItem,
    busy: Boolean,
    onApprove: (Boolean) -> Unit,
    onDeny: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Panel)
            .border(1.dp, Warn.copy(alpha = 0.6f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("agy needs your approval", color = Warn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        for (act in a.actions) {
            Text(act.label, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        when (a.state) {
            ApprovalState.PENDING -> {
                if (a.details.isNotBlank()) {
                    Text(a.details.lines().first(), color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApprove(false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Allow once")
                    }
                    OutlinedButton(onClick = { onApprove(true) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text("Always")
                    }
                }
                Text(
                    "Always allows: " + a.actions.joinToString { it.broadRule ?: it.rule },
                    color = Muted, fontSize = 11.sp,
                )
                TextButton(onClick = onDeny) { Text("Deny", color = Bad) }
            }
            ApprovalState.ALLOWED -> Text("Allowed. Asking agy to retry…", color = Accent, fontSize = 13.sp)
            ApprovalState.DENIED -> Text("Denied", color = Muted, fontSize = 13.sp)
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
