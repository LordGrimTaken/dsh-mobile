package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.SessionStatus
import dev.dsh.mobile.data.ToolActivity
import dev.dsh.mobile.data.ToolState
import dev.dsh.mobile.ui.theme.Danger
import dev.dsh.mobile.ui.theme.Warning

/**
 * Terminal wordmark: a mint `❯` in a kelp-ringed tile. The prompt glyph is the
 * brand — no gradient, no oversized art.
 */
@Composable
fun LogoMark(size: Dp = 30.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(primary.copy(alpha = 0.10f))
            .border(1.dp, primary.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "❯",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (size.value * 0.58f).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = primary,
        )
    }
}

@Composable
fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.Working -> MaterialTheme.colorScheme.primary
    SessionStatus.NeedsInput -> Warning
    SessionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
}

fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Working -> "Working"
    SessionStatus.NeedsInput -> "Needs input"
    SessionStatus.Idle -> "Idle"
}

@Composable
fun StatusDot(status: SessionStatus, size: Dp = 7.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(statusColor(status)),
    )
}

@Composable
fun WorkingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "working-dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val active = phase.toInt().coerceAtMost(2) == index
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 1f else 0.28f)),
            )
        }
    }
}

/** Bracketed terminal connection chip: `[ ● Live ]`. */
@Composable
fun ConnectionPill(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    val label = when (status) {
        ConnectionStatus.Live -> "Live"
        ConnectionStatus.Connecting -> "Connecting"
        ConnectionStatus.Offline -> "Offline"
        ConnectionStatus.Demo -> "Demo"
    }
    val color = when (status) {
        ConnectionStatus.Live -> MaterialTheme.colorScheme.primary
        ConnectionStatus.Connecting -> Warning
        ConnectionStatus.Offline -> Danger
        ConnectionStatus.Demo -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "[",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

/** Bracketed mono chip: `[ icon label ]`. */
@Composable
fun MiniChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "[",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.width(3.dp))
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "]",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** Tool ledger line: icon tile, mono title/detail and a mint check / red error. */
@Composable
fun ToolCallCard(tool: ToolActivity, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HadalIconTile(icon = Icons.Filled.Terminal, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tool.detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (tool.state) {
            ToolState.Running -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            ToolState.Done -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )

            ToolState.Failed -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Failed",
                tint = Danger,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private sealed interface Block {
    data class TextBlock(val text: String) : Block
    data class CodeBlock(val code: String) : Block
    data class HeadingBlock(val level: Int, val text: String) : Block
    data class ListBlock(val items: List<String>, val ordered: Boolean) : Block
    data class QuoteBlock(val text: String) : Block
    data class TableBlock(val header: List<String>, val rows: List<List<String>>) : Block
    data class TaskBlock(val checked: Boolean, val text: String) : Block
    data object DividerBlock : Block
}

private val INLINE = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
private val HEADING = Regex("^(#{1,4})\\s+(.+)$")
private val BULLET = Regex("^[-*]\\s+(.+)$")
private val TASK = Regex("^[-*]\\s+\\[([ xX])\\]\\s+(.+)$")
private val NUMBERED = Regex("^\\d+[.)]\\s+(.+)$")
private val RULE = Regex("^([-*_])\\1{2,}$")

private fun parseBlocks(raw: String): List<Block> {
    val parts = raw.split("```")
    return parts.flatMapIndexed { index, part ->
        when {
            part.isBlank() -> emptyList()
            index % 2 == 1 -> listOf(Block.CodeBlock(part.trim('\n', ' ')))
            else -> parseTextBlocks(part)
        }
    }
}

/** Lightweight block markdown: headings, bullets/numbers, quotes, rules, tables, paragraphs. */
private fun parseTextBlocks(raw: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = StringBuilder()
    val listItems = mutableListOf<String>()
    var listOrdered = false

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += Block.TextBlock(text)
        paragraph.clear()
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += Block.ListBlock(listItems.toList(), listOrdered)
            listItems.clear()
        }
    }

    val lines = raw.lines()
    var index = 0
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        if (isTableStart(lines, index)) {
            flushParagraph()
            flushList()
            val header = tableCells(lines[index])
            index += 2 // header + separator
            val rows = mutableListOf<List<String>>()
            while (index < lines.size) {
                val row = lines[index].trim()
                if (row.isEmpty() || !row.contains('|')) break
                rows += tableCells(row)
                index += 1
            }
            blocks += Block.TableBlock(header, rows)
            continue
        }
        val heading = HEADING.matchEntire(trimmed)
        val task = TASK.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(trimmed)
        val numbered = NUMBERED.matchEntire(trimmed)
        when {
            trimmed.isEmpty() -> {
                flushParagraph()
                flushList()
            }

            heading != null -> {
                flushParagraph()
                flushList()
                blocks += Block.HeadingBlock(heading.groupValues[1].length, heading.groupValues[2])
            }

            task != null -> {
                flushParagraph()
                flushList()
                blocks += Block.TaskBlock(
                    checked = task.groupValues[1].isNotBlank(),
                    text = task.groupValues[2],
                )
            }

            bullet != null -> {
                flushParagraph()
                if (listItems.isNotEmpty() && listOrdered) flushList()
                listOrdered = false
                listItems += bullet.groupValues[1]
            }

            numbered != null -> {
                flushParagraph()
                if (listItems.isNotEmpty() && !listOrdered) flushList()
                listOrdered = true
                listItems += numbered.groupValues[1]
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                flushList()
                blocks += Block.QuoteBlock(trimmed.removePrefix(">").trim())
            }

            RULE.matches(trimmed) -> {
                flushParagraph()
                flushList()
                blocks += Block.DividerBlock
            }

            else -> {
                flushList()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        index += 1
    }
    flushParagraph()
    flushList()
    return blocks
}

/** A GFM pipe table starts with a cell row followed by a `--- | :---:` separator row. */
private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val header = lines[index].trim()
    val separator = lines[index + 1].trim()
    if (!header.contains('|') || !separator.contains('|') && !separator.matches(TABLE_SEPARATOR)) {
        return false
    }
    return separator.matches(TABLE_SEPARATOR) && separator.count { it == '-' } >= 3
}

private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private val URL = Regex("https?://[^\\s<>()]+")

private fun AnnotatedString.Builder.appendLinked(text: String, linkColor: Color) {
    var last = 0
    URL.findAll(text).forEach { match ->
        if (match.range.first > last) {
            append(text.substring(last, match.range.first))
        }
        val raw = match.value
        val url = raw.trimEnd('.', ',', ';', ':', '!', '?', ')')
        val trailing = raw.substring(url.length)
        withLink(LinkAnnotation.Url(url)) {
            pushStyle(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            append(url)
            pop()
        }
        if (trailing.isNotEmpty()) append(trailing)
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

private fun inlineText(
    text: String,
    color: Color,
    inlineCodeColor: Color,
    linkColor: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var last = 0
    INLINE.findAll(text).forEach { match ->
        if (match.range.first > last) {
            builder.appendLinked(text.substring(last, match.range.first), linkColor)
        }
        val token = match.value
        if (token.startsWith("**")) {
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color))
            builder.append(token.substring(2, token.length - 2))
            builder.pop()
        } else {
            builder.pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = inlineCodeColor,
                ),
            )
            builder.append(token.substring(1, token.length - 1))
            builder.pop()
        }
        last = match.range.last + 1
    }
    if (last < text.length) {
        builder.appendLinked(text.substring(last), linkColor)
    }
    return builder.toAnnotatedString()
}

/** When true, fenced code blocks soft-wrap instead of scrolling horizontally. */
val LocalWrapCodeBlocks = compositionLocalOf { false }

@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val inlineCodeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
    val linkColor = MaterialTheme.colorScheme.primary
    val ruleColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.TextBlock -> Text(
                    inlineText(block.text, color, inlineCodeColor, linkColor),
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )

                is Block.HeadingBlock -> Text(
                    inlineText(block.text, color, inlineCodeColor, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = if (block.level <= 2) color else MaterialTheme.colorScheme.primary,
                )

                is Block.ListBlock -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                modifier = Modifier.width(20.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                inlineText(item, color, inlineCodeColor, linkColor),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = color,
                            )
                        }
                    }
                }

                is Block.TaskBlock -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (block.checked) "☑" else "☐",
                        modifier = Modifier.width(22.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (block.checked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                    Text(
                        inlineText(block.text, color, inlineCodeColor, linkColor),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (block.checked) {
                                TextDecoration.LineThrough
                            } else {
                                TextDecoration.None
                            },
                        ),
                        color = if (block.checked) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            color
                        },
                    )
                }

                is Block.TableBlock -> {
                    val columns = maxOf(
                        block.header.size,
                        block.rows.maxOfOrNull { it.size } ?: 0,
                    ).coerceAtLeast(1)
                    val widths = (0 until columns).map { column ->
                        val maxChars = (listOf(block.header) + block.rows)
                            .maxOf { it.getOrNull(column)?.length ?: 0 }
                        ((maxChars.coerceAtMost(28)) * 7 + 24).coerceIn(56, 230).dp
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    ) {
                        TableCellsRow(
                            cells = block.header,
                            widths = widths,
                            header = true,
                            color = color,
                            inlineCodeColor = inlineCodeColor,
                            linkColor = linkColor,
                        )
                        block.rows.forEach { row ->
                            TableCellsRow(
                                cells = row,
                                widths = widths,
                                header = false,
                                color = color,
                                inlineCodeColor = inlineCodeColor,
                                linkColor = linkColor,
                            )
                        }
                    }
                }

                is Block.QuoteBlock -> Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineText(block.text, color, inlineCodeColor, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Block.DividerBlock -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                is Block.CodeBlock -> {
                    val shape = RoundedCornerShape(8.dp)
                    val wrapCode = LocalWrapCodeBlocks.current
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                            .drawBehind {
                                drawRect(
                                    color = ruleColor,
                                    size = Size(2.dp.toPx(), size.height),
                                )
                            },
                    ) {
                        Text(
                            block.code,
                            modifier = Modifier
                                .then(
                                    if (wrapCode) {
                                        Modifier.fillMaxWidth()
                                    } else {
                                        Modifier.horizontalScroll(rememberScrollState())
                                    },
                                )
                                .padding(start = 14.dp, top = 10.dp, end = 40.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = {
                                copyCode(context, block.code)
                                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(30.dp),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy code",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCellsRow(
    cells: List<String>,
    widths: List<Dp>,
    header: Boolean,
    color: Color,
    inlineCodeColor: Color,
    linkColor: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            ),
    ) {
        widths.forEachIndexed { column, width ->
            Text(
                inlineText(
                    cells.getOrNull(column).orEmpty(),
                    if (header) MaterialTheme.colorScheme.primary else color,
                    inlineCodeColor,
                    linkColor,
                ),
                modifier = Modifier
                    .width(width)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                style = if (header) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = if (header) MaterialTheme.colorScheme.primary else color,
            )
        }
    }
}

private fun copyCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH code", code))
}

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    working: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    attachEnabled: Boolean = true,
    onAttachClick: () -> Unit = {},
    sendEnabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier.then(
            if (focused) {
                Modifier.shadow(
                    elevation = 12.dp,
                    shape = shape,
                    ambientColor = primary,
                    spotColor = primary,
                )
            } else {
                Modifier
            },
        ),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(
            1.dp,
            if (focused) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "❯",
                modifier = Modifier.padding(start = 6.dp, bottom = 13.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = primary,
            )
            Spacer(Modifier.width(7.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .padding(vertical = 11.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(primary),
                maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "Message DSH…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(onClick = onAttachClick, enabled = attachEnabled) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (attachEnabled) 0.9f else 0.35f,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
            val sendActive = working || sendEnabled
            FilledIconButton(
                onClick = when {
                    working -> onStop
                    sendEnabled -> onSend
                    else -> ({})
                },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (sendActive) {
                        primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (working) {
                        MaterialTheme.colorScheme.onSurface
                    } else if (sendEnabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            ) {
                Icon(
                    if (working) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (working) "Stop" else "Send",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
