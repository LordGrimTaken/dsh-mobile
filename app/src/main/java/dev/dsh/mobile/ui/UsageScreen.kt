package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.HostUsage
import dev.dsh.mobile.data.SessionUsage
import dev.dsh.mobile.data.TokenUsage
import dev.dsh.mobile.data.relativeLabel
import dev.dsh.mobile.ui.theme.Warning
import kotlinx.coroutines.delay
import java.text.NumberFormat

/**
 * DeepSeek usage dashboard: local token totals from the host's tokenUsage projections
 * plus the remaining API credit when the host process has DEEPSEEK_API_KEY configured.
 */
@Composable
fun UsageScreen(state: AppState, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        state.refreshUsage()
        // Keep token totals and remaining credit fresh while the dashboard stays open.
        while (true) {
            delay(30_000)
            if (state.isLive) state.refreshUsage()
        }
    }
    val context = LocalContext.current
    val usage = state.usage

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "USAGE",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    state.hostLabel ?: if (state.isLive) "Live host" else "Demo data",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (state.usageLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                if (usage != null) {
                    IconButton(
                        onClick = {
                            copyUsageSummary(context, usage, state.todayTokens)
                            Toast.makeText(context, "Usage summary copied", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy usage summary",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    IconButton(onClick = { shareUsageCsv(context, usage) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Share usage CSV",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                IconButton(onClick = state::refreshUsage) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh usage",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            usage == null && state.usageLoading -> CenteredMessage("Loading usage from the host…")
            usage == null && state.usageError != null -> CenteredMessage(
                state.usageError ?: "Could not load usage",
                actionLabel = "Retry",
                onAction = state::refreshUsage,
            )
            usage == null -> CenteredMessage(
                "No usage data yet.",
                actionLabel = "Refresh",
                onAction = state::refreshUsage,
            )
            else -> UsageBody(usage, state.usageError, state.todayTokens)
        }
    }
}

@Composable
private fun UsageBody(usage: HostUsage, error: String?, todayTokens: Long) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 28.dp),
    ) {
        if (error != null) {
            item {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item { BalanceCard(usage) }
        item { LowBalanceWarning(usage) }
        item { TotalsCard(usage.totals) }
        item { TodayTokensCard(todayTokens) }
        item {
            Text(
                "BY SESSION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
        if (usage.sessions.isEmpty()) {
            item {
                Text(
                    "No token usage has been recorded on this host yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        } else {
            items(
                usage.sessions.sortedByDescending { it.usage.totalTokens },
                key = { it.sessionId },
            ) { session ->
                SessionUsageRow(session)
            }
        }
        item {
            Text(
                "Token totals come from DSH's per-session tokenUsage projections. Remaining " +
                    "credit comes from the signed-in Platform wallet, or DEEPSEEK_API_KEY when " +
                    "configured; the key never leaves the host.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

@Composable
private fun LowBalanceWarning(usage: HostUsage) {
    val low = usage.balance?.balances.orEmpty().filter { info ->
        val amount = info.totalBalance.toDoubleOrNull() ?: return@filter false
        amount < LOW_BALANCE_WARNING
    }
    if (low.isEmpty()) return
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "LOW REMAINING CREDIT",
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                low.joinToString(" · ") { "${formatBalance(it.totalBalance)} ${it.currency}" } +
                    " left — top up soon to avoid interrupted turns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val LOW_BALANCE_WARNING = 5.0

/** Column count of the shared usage CSV: a fixed-width table with a `section` discriminator. */
private const val USAGE_CSV_COLUMNS = 15

private val USAGE_CSV_HEADER: String = listOf(
    "section",
    "currency",
    "total",
    "granted",
    "toppedUp",
    "available",
    "totalTokens",
    "uncachedInputTokens",
    "promptTokens",
    "outputTokens",
    "cacheReadTokens",
    "cacheWriteTokens",
    "title",
    "sessionId",
    "updatedAt",
).joinToString(",")

private fun copyUsageSummary(context: Context, usage: HostUsage, todayTokens: Long) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH usage", usageSummary(usage, todayTokens)))
}

private fun usageSummary(usage: HostUsage, todayTokens: Long): String = buildString {
    appendLine("DSH Mobile usage")
    appendLine("Today: $todayTokens tokens since first sync today (approximate)")
    usage.balance?.balances?.forEach { info ->
        appendLine(
            "Balance: ${formatBalance(info.totalBalance)} ${info.currency} " +
                "(granted ${formatBalance(info.grantedBalance)}, " +
                "topped up ${formatBalance(info.toppedUpBalance)})",
        )
    }
    appendLine("Total tokens: ${NumberFormat.getIntegerInstance().format(usage.totals.totalTokens)}")
    appendLine("Prompt (uncached): ${NumberFormat.getIntegerInstance().format(usage.totals.uncachedInputTokens)}")
    appendLine("Completion: ${NumberFormat.getIntegerInstance().format(usage.totals.outputTokens)}")
    appendLine("Cache read: ${NumberFormat.getIntegerInstance().format(usage.totals.cacheReadTokens)}")
    appendLine("Sessions: ${usage.sessions.size}")
    usage.sessions.sortedByDescending { it.usage.totalTokens }.take(5).forEach { session ->
        appendLine("- ${session.title}: ${NumberFormat.getIntegerInstance().format(session.usage.totalTokens)}")
    }
}

/** Hands the usage export to whatever app can accept `text/csv` through the system chooser. */
private fun shareUsageCsv(context: Context, usage: HostUsage) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_TEXT, usageCsv(usage))
    }
    context.startActivity(Intent.createChooser(send, "Share usage CSV"))
}

/**
 * Fixed-width CSV export of the usage screen: one row per balance currency (`balance`), one row
 * for the host totals (`totals`) and one row per session (`sessions`). Columns that do not apply
 * to a section stay empty so every row parses as the same 15-column table.
 */
private fun usageCsv(usage: HostUsage): String = buildString {
    appendLine(USAGE_CSV_HEADER)
    usage.balance?.let { balance ->
        balance.balances.forEach { info ->
            appendLine(
                csvRow(
                    "balance",
                    info.currency,
                    info.totalBalance,
                    info.grantedBalance,
                    info.toppedUpBalance,
                    balance.available.toString(),
                ),
            )
        }
    }
    val totals = usage.totals
    appendLine(
        csvRow(
            "totals",
            "",
            "",
            "",
            "",
            "",
            totals.totalTokens.toString(),
            totals.uncachedInputTokens.toString(),
            totals.promptTokens.toString(),
            totals.outputTokens.toString(),
            totals.cacheReadTokens.toString(),
            totals.cacheWriteTokens.toString(),
        ),
    )
    usage.sessions.sortedByDescending { it.usage.totalTokens }.forEach { session ->
        val tokens = session.usage
        appendLine(
            csvRow(
                "sessions",
                "",
                "",
                "",
                "",
                "",
                tokens.totalTokens.toString(),
                tokens.uncachedInputTokens.toString(),
                tokens.promptTokens.toString(),
                tokens.outputTokens.toString(),
                tokens.cacheReadTokens.toString(),
                tokens.cacheWriteTokens.toString(),
                session.title,
                session.sessionId,
                session.updatedAt,
            ),
        )
    }
}

private fun csvRow(vararg fields: String): String {
    val cells = ArrayList<String>(USAGE_CSV_COLUMNS)
    fields.forEach { cells.add(csvField(it)) }
    while (cells.size < USAGE_CSV_COLUMNS) cells.add("")
    return cells.joinToString(",")
}

/** RFC 4180 quoting, plus spreadsheet formula-injection hardening for the first character. */
private fun csvField(value: String): String {
    val guarded = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) {
        "'$value"
    } else {
        value
    }
    return if (guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + guarded.replace("\"", "\"\"") + "\""
    } else {
        guarded
    }
}

@Composable
private fun BalanceCard(usage: HostUsage) {
    val balance = usage.balance
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "DEEPSEEK BALANCE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            when {
                balance != null && balance.balances.isNotEmpty() -> {
                    balance.balances.forEach { info ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatBalance(info.totalBalance),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                info.currency,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (balance.available) "available" else "insufficient",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (balance.available) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Text(
                            "granted ${formatBalance(info.grantedBalance)} · " +
                                "topped up ${formatBalance(info.toppedUpBalance)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                usage.balanceError != null -> Text(
                    "Balance unavailable: ${usage.balanceError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    "No API balance configured. Set DEEPSEEK_API_KEY in the host environment " +
                        "to show the remaining DeepSeek credit here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(totals: TokenUsage) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "TOKEN USAGE · ALL SESSIONS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                formatTokens(totals.totalTokens),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "total tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(10.dp))
            MetricRow("Prompt (uncached)", totals.uncachedInputTokens)
            MetricRow("Completion", totals.outputTokens)
            MetricRow("Cache read", totals.cacheReadTokens)
            MetricRow("Cache write", totals.cacheWriteTokens)
        }
    }
}

@Composable
private fun TodayTokensCard(todayTokens: Long) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "TODAY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                formatTokens(todayTokens),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "tokens since the first sync today (approximate)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SessionUsageRow(session: SessionUsage) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        relativeLabel(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTokens(session.usage.totalTokens),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatTokens(session.usage.uncachedInputTokens)} in · " +
                    "${formatTokens(session.usage.outputTokens)} out · " +
                    "${formatTokens(session.usage.cacheReadTokens)} cache",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatTokens(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable(onClick = onAction),
            )
        }
    }
}

private fun formatTokens(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatBalance(value: String): String = value
