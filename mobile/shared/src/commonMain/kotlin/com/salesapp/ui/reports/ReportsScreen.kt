package com.salesapp.ui.reports

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.ReportGroup
import com.salesapp.data.ReportInfo
import com.salesapp.resources.Res
import com.salesapp.resources.reports_no_match
import com.salesapp.resources.reports_none
import com.salesapp.resources.reports_search
import com.salesapp.resources.reports_targets
import com.salesapp.resources.reports_title
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ReportsPage(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onTargets: () -> Unit,
    viewModel: ReportsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportsScreen(state, viewModel::onEvent, onBack, onOpen, onTargets)
}

// Every report the person may open, in the server's own groups, with a search by title.
@Composable
fun ReportsScreen(
    state: ReportsState,
    onEvent: (ReportsEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
    onTargets: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.reports_title), onBack = onBack)
        SearchField(
            value = state.query,
            onValueChange = { onEvent(ReportsEvent.QueryChanged(it)) },
            placeholder = stringResource(Res.string.reports_search),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.groups.isEmpty() -> item { LoadingState() }
                state.error != null && state.groups.isEmpty() -> item {
                    ErrorState(state.error, onRetry = { onEvent(ReportsEvent.Retry) })
                }
                state.none -> item { EmptyState(stringResource(Res.string.reports_none)) }
                state.nothingFound -> item { EmptyState(stringResource(Res.string.reports_no_match)) }
                else -> state.shown.forEach { group ->
                    item(key = "group-${group.key}") { GroupHeader(group.title) }
                    items(group.reports, key = { it.key }) { report -> ReportRow(report, onOpen) }
                }
            }
        }
        // Targets are not a report, but they belong with them for the people who set them.
        if (state.canSetTargets) {
            SecondaryButton(stringResource(Res.string.reports_targets), onClick = onTargets, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ReportRow(report: ReportInfo, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = { onOpen(report.key) }) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(report.title, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            if (report.description.isNotBlank()) {
                Text(
                    report.description,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal val sampleGroups = listOf(
    ReportGroup(
        key = "sales",
        title = "Sales",
        reports = listOf(
            ReportInfo(
                key = "sales-by-salesman",
                title = "Sales by salesman",
                description = "What each salesman sold, before VAT",
                filters = listOf("dates", "company", "person"),
                personLabel = "Salesman",
            ),
            ReportInfo(
                key = "sales-by-product",
                title = "Sales by product",
                description = "Cartons and value of each product",
                filters = listOf("dates", "company", "product"),
            ),
        ),
    ),
    ReportGroup(
        key = "stock",
        title = "Stock",
        reports = listOf(
            ReportInfo(
                key = "low-stock",
                title = "Low stock",
                description = "Products below their low stock level",
                filters = listOf("company"),
            ),
        ),
    ),
)

@Preview
@Composable
private fun ReportsPreview() {
    SalesTheme {
        ReportsScreen(ReportsState(loading = false, groups = sampleGroups, canSetTargets = true), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun ReportsSearchPreview() {
    SalesTheme {
        ReportsScreen(ReportsState(loading = false, groups = sampleGroups, query = "sales"), onEvent = {}, onBack = {})
    }
}
