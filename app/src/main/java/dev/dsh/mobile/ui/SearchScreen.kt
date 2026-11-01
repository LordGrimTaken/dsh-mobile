package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.HostSearchHit
import dev.dsh.mobile.data.HOST_SEARCH_MIN_QUERY_CHARS
import dev.dsh.mobile.data.canLoadMoreHostSearch
import dev.dsh.mobile.data.hostSearchHighlightRanges
import dev.dsh.mobile.data.hostSearchQuery
import dev.dsh.mobile.data.searchShowingNote
import dev.dsh.mobile.data.SEARCH_FILTER_ROLES
import dev.dsh.mobile.data.SEARCH_UNDATED_NOTE
import dev.dsh.mobile.data.SearchDatePreset
import dev.dsh.mobile.data.searchActiveFilterSummary
import dev.dsh.mobile.data.searchCoverageNote
import dev.dsh.mobile.data.searchDatePolicyHint
import dev.dsh.mobile.data.searchFiltersIgnored
import dev.dsh.mobile.data.searchHasFilters
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Host-wide message search: searches every session's transcript on the host (as opposed to the
 * session-list title filter and the cached-transcript search). Debounced, with an explicit IME
 * search/retry, highlighted snippets and tap-to-open-session.
 */
@Composable
fun HostSearchScreen(
    state: AppState,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    val result = state.hostSearchResult
    val loading = state.hostSearchLoading
    val loadingMore = state.hostSearchLoadingMore
    val loadMoreError = state.hostSearchLoadMoreError
    val error = state.hostSearchError
    val validQuery = hostSearchQuery(query)
    val activeFilterSummary =
        searchActiveFilterSummary(state.hostSearchRoles, state.hostSearchDatePreset)
    val hasFilters = searchHasFilters(state.hostSearchRoles, state.hostSearchDatePreset)
    // S14: old hosts ignore role/since/until and return no `filters` echo. Don't present those
    // results as filtered; surface the notice instead.
    val filtersIgnored = searchFiltersIgnored(hasFilters, result)
    val appliedFilterSummary = activeFilterSummary?.takeIf { !filtersIgnored }
    val datePolicyHint = searchDatePolicyHint(state.hostSearchDatePreset)
    // Host 0.2.7: honest coverage warning when the host could not scan every session.
    val coverageNote = searchCoverageNote(result)

    // Debounced search; short/blank queries clear the previous result and cancel in-flight work.
    LaunchedEffect(query) {
        val effective = hostSearchQuery(query)
        if (effective == null) {
            state.clearHostSearch()
        } else {
            delay(350)
            state.runHostSearch(query)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "❯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "SEARCH ALL SESSIONS",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            result?.hits?.takeIf { it.isNotEmpty() }?.let { hits ->
                Text(
                    if (result?.truncated == true) "${hits.size}+ matches" else "${hits.size} matches",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            ConnectionPill(state.connection)
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it.take(200)
                if (it.trim().length < HOST_SEARCH_MIN_QUERY_CHARS) state.clearHostSearch()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            singleLine = true,
            placeholder = {
                Text(
                    "Search messages on the host…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; state.clearHostSearch() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { state.runHostSearch(query, force = true) },
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(10.dp),
        )

        // Compact filters: role chips (All is exclusive) and date-preset chips. Any change
        // immediately re-runs the current query (AppState cancels the in-flight request).
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchFilterChip(
                label = "All roles",
                selected = state.hostSearchRoles.isEmpty(),
                onClick = { state.selectAllHostSearchRoles() },
            )
            SEARCH_FILTER_ROLES.forEach { role ->
                SearchFilterChip(
                    label = role.replaceFirstChar { it.uppercase() },
                    selected = role in state.hostSearchRoles,
                    onClick = { state.toggleHostSearchRole(role) },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchDatePreset.entries.forEach { preset ->
                SearchFilterChip(
                    label = preset.label,
                    selected = state.hostSearchDatePreset == preset,
                    onClick = { state.updateHostSearchDatePreset(preset) },
                )
            }
            if (hasFilters) {
                Text(
                    "Clear filters",
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { state.clearHostSearchFilters() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        datePolicyHint?.let { hint ->
            Text(
                "$hint $SEARCH_UNDATED_NOTE",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (filtersIgnored) {
            Text(
                "This host didn't apply your filters. Update the desktop host plugin.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            appliedFilterSummary?.let { summary ->
                Text(
                    summary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        coverageNote?.let { note ->
            Text(
                note,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        val errorMessage = error
        when {
            loading -> Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Searching the host…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            errorMessage != null -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    errorMessage,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Retry",
                    modifier = Modifier
                        .clickable { state.retryHostSearch() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            result?.supported == false -> SearchEmptyState(
                title = "Message search isn't available on this host yet",
                body = "Update the desktop host plugin to search across every session from the phone.",
            )

            validQuery == null -> SearchEmptyState(
                title = "Search every session on the host",
                body = "Type at least $HOST_SEARCH_MIN_QUERY_CHARS characters. This searches " +
                    "message text on the desktop; the list search above only filters titles.",
                filters = appliedFilterSummary,
            )

            result != null && result.hits.isEmpty() -> SearchEmptyState(
                title = "No matches for \"$validQuery\"",
                body = if (filtersIgnored) {
                    "This host doesn't support search filters; results are unfiltered."
                } else {
                    "Try a different word or phrase, or clear the filters."
                },
                filters = appliedFilterSummary,
            )

            result != null -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (result.truncated) {
                    item(key = "truncated") {
                        Column {
                            Text(
                                "Showing the first ${result.hits.size} matches.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            appliedFilterSummary?.let { summary ->
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                items(
                    result.hits,
                    key = { hit -> "${hit.sessionId}:${hit.messageId}:${hit.snippet.hashCode()}" },
                ) { hit ->
                    HostSearchHitCard(
                        hit = hit,
                        query = validQuery.orEmpty(),
                        onClick = {
                            // J13: arm the jump for seq or messageId-only hits; open normally only
                            // when the hit carries neither key.
                            if ((hit.seq ?: 0) > 0 || hit.messageId.isNotBlank()) {
                                state.openSearchHit(hit.sessionId, hit.seq, hit.messageId)
                            } else {
                                state.closeHostSearch()
                                onOpenSession(hit.sessionId)
                            }
                        },
                    )
                }
                // Pagination footer: only for hosts that advertise more pages; old hosts keep the
                // exact previous UI. Existing hits stay visible while a page loads or fails.
                if (result.hits.isNotEmpty() &&
                    (result.hasMore || loadingMore || loadMoreError != null)
                ) {
                    item(key = "pagination") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                searchShowingNote(result.hits.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            when {
                                loadingMore -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Loading more…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }

                                loadMoreError != null -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Couldn't load more. ${loadMoreError.orEmpty()}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        "Retry",
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { state.retryHostSearchLoadMore() }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                canLoadMoreHostSearch(result, loading, loadingMore) -> Text(
                                    "Load more",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { state.loadMoreHostSearch() }
                                        .padding(vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostSearchHitCard(
    hit: HostSearchHit,
    query: String,
    onClick: () -> Unit,
) {
    HadalCard(modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            hit.sessionTitle ?: hit.sessionId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        HighlightedSnippet(text = hit.snippet, query = query)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(hit.role.uppercase())
                searchHitTimeLabel(hit.createdAt)?.let { time ->
                    append(" · ")
                    append(time)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
    }
}

/** Snippet with every (case-insensitive) query occurrence emphasized cheaply. */
@Composable
private fun HighlightedSnippet(text: String, query: String) {
    val highlight = MaterialTheme.colorScheme.primary
    val ranges = hostSearchHighlightRanges(text, query)
    val annotated = buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) append(text.substring(cursor, range.first))
            withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SearchEmptyState(title: String, body: String, filters: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "❯",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        filters?.let { summary ->
            Spacer(Modifier.height(6.dp))
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SearchFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

private val SEARCH_HIT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US).withZone(ZoneId.systemDefault())

private fun searchHitTimeLabel(createdAt: String?): String? {
    val raw = createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { SEARCH_HIT_TIME_FORMAT.format(Instant.parse(raw)) }.getOrElse { raw }
}
