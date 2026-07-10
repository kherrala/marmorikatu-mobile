package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.rememberMkInteractionSource

/**
 * A small, dependency-free Markdown renderer for the announcement / news text
 * the AI produces: ATX headings (`#`..`###`), bullet lists (`-`, `*`), ordered
 * lists, blank-line-separated paragraphs, and inline `**bold**`, `*italic*` and
 * `` `code` ``. Anything it doesn't recognise renders as plain text, so unknown
 * markup degrades gracefully rather than showing raw symbols in the wrong place.
 */
@Composable
fun MkMarkdown(text: String, modifier: Modifier = Modifier) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = parseInline(block.text),
                    style = when (block.level) {
                        1 -> type.title
                        2 -> type.heading
                        else -> type.heading.copy(fontSize = type.body.fontSize)
                    },
                    color = colors.inkHi,
                )
                is MdBlock.Bullet -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(block.marker, style = type.body, color = colors.accent)
                    Text(parseInline(block.text), style = type.body, color = colors.inkMid)
                }
                is MdBlock.Paragraph -> Text(
                    text = parseInline(block.text),
                    style = type.body,
                    color = colors.inkMid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * A dialog that shows the full [title] + markdown [body] of an announcement or
 * news item — the way to read text that the feed truncates to a line. Tap the
 * scrim or "Sulje" to dismiss; an optional "Lue" reads it aloud.
 */
@Composable
fun MkArticleViewer(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onRead: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val c = MkTheme.colors
    val type = MkTheme.type
    val shape = RoundedCornerShape(MkRadius.xl)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .clickable(rememberMkInteractionSource(), indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .clip(shape)
                    .background(c.surfaceCard)
                    .border(1.dp, c.borderSubtle, shape)
                    // Swallow taps so the card itself never dismisses the dialog.
                    .clickable(rememberMkInteractionSource(), indication = null, onClick = {})
                    .padding(20.dp),
            ) {
                Text(title, style = type.title, color = c.inkHi)
                if (!meta.isNullOrBlank()) {
                    Text(meta, style = type.readout(11), color = c.inkLo, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MkMarkdown(body)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onRead != null) {
                        MkButton(
                            text = "Lue",
                            onClick = onRead,
                            variant = MkButtonVariant.Secondary,
                            size = MkButtonSize.Md,
                            icon = fi.marmorikatu.app.icons.MkIcons.SpeakerHigh,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    MkButton(
                        text = "Sulje",
                        onClick = onDismiss,
                        variant = MkButtonVariant.Primary,
                        size = MkButtonSize.Md,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private val ORDERED = Regex("""^(\d+)[.)]\s+(.*)""")

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
        para.setLength(0)
    }
    for (rawLine in md.replace("\r\n", "\n").split("\n")) {
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                blocks += MdBlock.Bullet("•", trimmed.drop(2).trim())
            }
            ORDERED.matches(trimmed) -> {
                flush()
                val m = ORDERED.find(trimmed)!!
                blocks += MdBlock.Bullet("${m.groupValues[1]}.", m.groupValues[2].trim())
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
    }
    flush()
    return blocks
}

/** Inline `**bold**` and `*italic*` / `_italic_` → styled spans; the rest is literal. */
private fun parseInline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val rest = s.substring(i)
        val bold = matchDelimited(rest, "**")
        val italic = when {
            rest.startsWith("*") && !rest.startsWith("**") -> matchDelimited(rest, "*")
            rest.startsWith("_") -> matchDelimited(rest, "_")
            else -> null
        }
        when {
            bold != null -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(bold.first); pop()
                i += bold.second
            }
            italic != null -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(italic.first); pop()
                i += italic.second
            }
            else -> {
                append(s[i]); i += 1
            }
        }
    }
}

/** If [rest] opens with [delim], returns (inner text, chars consumed) up to the closing [delim]. */
private fun matchDelimited(rest: String, delim: String): Pair<String, Int>? {
    if (!rest.startsWith(delim)) return null
    val close = rest.indexOf(delim, startIndex = delim.length)
    if (close < delim.length) return null
    val inner = rest.substring(delim.length, close)
    if (inner.isEmpty()) return null
    return inner to (close + delim.length)
}
