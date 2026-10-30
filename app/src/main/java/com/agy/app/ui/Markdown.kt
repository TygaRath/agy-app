package com.agy.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import com.agy.app.BridgeClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Block {
    data class Prose(val text: String) : Block
    data class Code(val lang: String, val code: String) : Block
    data class Image(val src: String) : Block
}

// A line that is only an image, or only a link to an image file.
private val imageLine = Regex("""^\s*!\[([^\]]*)\]\(([^)]+)\)\s*$""")
private val imageLink = Regex(
    """^\s*\[([^\]]*)\]\(([^)]+\.(?:png|jpe?g|gif|webp|bmp))\)\s*$""",
    RegexOption.IGNORE_CASE,
)

/** Splits text into prose and ``` fenced code blocks. An unclosed fence (mid-stream) still renders as code. */
private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""

    fun flush() {
        if (inCode) {
            blocks += Block.Code(lang, buf.toString().trimEnd('\n'))
        } else if (buf.isNotBlank()) {
            blocks += Block.Prose(buf.toString().trim('\n'))
        }
        buf.clear()
    }

    for (line in text.split("\n")) {
        if (line.trimStart().startsWith("```")) {
            flush()
            inCode = !inCode
            lang = if (inCode) line.trim().removePrefix("```").trim() else ""
        } else if (!inCode) {
            val m = imageLine.matchEntire(line) ?: imageLink.matchEntire(line)
            if (m != null) {
                flush()
                blocks += Block.Image(m.groupValues[2].trim())
            } else {
                buf.append(line).append('\n')
            }
        } else {
            buf.append(line).append('\n')
        }
    }
    flush()
    return blocks
}

private val inlineRegex = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)""")

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = UserBubble, color = Accent)
            ) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(color = Accent)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

private fun prose(text: String): AnnotatedString = buildAnnotatedString {
    text.split("\n").forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("#") -> {
                val title = trimmed.trimStart('#').trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) { append(inline(title)) }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append(" ".repeat(indent) + "•  ")
                append(inline(trimmed.drop(2)))
            }
            else -> append(inline(line))
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in parseBlocks(text)) {
            when (block) {
                is Block.Prose -> SelectionContainer {
                    Text(prose(block.text), fontSize = 15.sp, lineHeight = 22.sp)
                }
                is Block.Code -> CodeBlock(block.lang, block.code)
                is Block.Image -> AgentImage(block.src)
            }
        }
    }
}

@Composable
private fun AgentImage(src: String) {
    val token = LocalBridgeToken.current
    val model: String = if (src.startsWith("http://") || src.startsWith("https://")) {
        src
    } else {
        BridgeClient.fileUrl(Uri.decode(src.removePrefix("file://")), token)
    }
    ImageThumb(
        model = model,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang.ifBlank { "code" }, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
        SelectionContainer {
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}
