package com.salesapp.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.SearchGroup
import com.salesapp.data.SearchItem
import com.salesapp.resources.Res
import com.salesapp.resources.search_group
import com.salesapp.resources.search_group_more
import com.salesapp.resources.search_hint
import com.salesapp.resources.search_nothing
import com.salesapp.resources.search_short
import com.salesapp.resources.search_title
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchPage(
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SearchEffect.Open -> onOpen(effect.route)
            }
        }
    }
    SearchScreen(state, viewModel::onEvent, onBack)
}

// One box over everything: customers, orders, invoices, delivery notes, trips, returns, products and
// staff. The server decides what this person may be shown and writes each line in plain words.
@Composable
fun SearchScreen(state: SearchState, onEvent: (SearchEvent) -> Unit, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    // The keyboard is up as soon as the screen opens, so nobody has to tap twice.
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.search_title), onBack = onBack)
        SearchField(
            value = state.query,
            onValueChange = { onEvent(SearchEvent.QueryChanged(it)) },
            placeholder = stringResource(Res.string.search_hint),
            modifier = Modifier.focusRequester(focus).padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.tooShort -> item {
                    Text(
                        stringResource(Res.string.search_short),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.error != null -> item { Say(state.error, error = true) }
                state.searching && state.groups.isEmpty() -> item { LoadingState() }
                state.nothing -> item { EmptyState(stringResource(Res.string.search_nothing)) }
                else -> state.groups.forEach { group ->
                    item(key = "group-${group.key}") { GroupLabel(group) }
                    items(group.items, key = { "${group.key}-${it.id}" }) { item ->
                        Result(item) { onEvent(SearchEvent.OpenItem(group.key, item.id)) }
                    }
                }
            }
        }
    }
}

// The kind of thing found, and how many there are of it: "Customers · 8 of 23".
@Composable
private fun GroupLabel(group: SearchGroup) {
    SectionLabel(
        if (group.more) {
            stringResource(Res.string.search_group_more, group.title, group.items.size, group.count)
        } else {
            stringResource(Res.string.search_group, group.title, group.count)
        },
    )
}

@Composable
private fun Result(item: SearchItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            if (item.detail.isNotBlank()) {
                Text(item.detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
        }
    }
}

private val sampleGroups = listOf(
    SearchGroup(
        key = "customers",
        title = "Customers",
        count = 23,
        items = listOf(
            SearchItem(1, "Area 47 Superstore", "Area 47, Lilongwe · Supermarket · 0991 234 567"),
            SearchItem(2, "Salima Shop", "Salima · Upcountry"),
        ),
    ),
    SearchGroup(
        key = "invoices",
        title = "Invoices",
        count = 2,
        items = listOf(
            SearchItem(9, "LV-INV-000021", "Area 47 Superstore · 4 Oct 2026 · MWK 1,392,000 · MWK 392,000 to pay"),
            SearchItem(10, "LV-INV-000022", "Salima Shop · 11 Oct 2026 · MWK 498,800 · Paid"),
        ),
    ),
)

@Preview
@Composable
private fun SearchShortPreview() {
    SalesTheme { SearchScreen(SearchState(query = "a"), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun SearchResultsPreview() {
    SalesTheme { SearchScreen(SearchState(query = "area", groups = sampleGroups, answered = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun SearchNothingPreview() {
    SalesTheme { SearchScreen(SearchState(query = "zzzz", answered = true), onEvent = {}, onBack = {}) }
}
