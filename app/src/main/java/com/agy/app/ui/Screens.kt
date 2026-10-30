package com.agy.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(16.dp)
        ) {
            SelectionContainer {
                Text(SETUP_CMD, fontFamily = FontFamily.Monospace, color = Accent)
            }
        }
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
