package com.salesapp.ui.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.AlertCounts
import com.salesapp.data.AlertRow
import com.salesapp.resources.Res
import com.salesapp.resources.alerts_lead
import com.salesapp.resources.alerts_none
import com.salesapp.resources.alerts_title
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AlertsPage(
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    viewModel: AlertsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AlertsEffect.Open -> onOpen(effect.route)
            }
        }
    }
    AlertsScreen(state, viewModel::onEvent, onBack)
}

// The worst first: money or stock at risk, then work waiting, then what is only worth knowing.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(state: AlertsState, onEvent: (AlertsEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.alerts_title), onBack = onBack)
        Text(
            stringResource(Res.string.alerts_lead),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onEvent(AlertsEvent.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.loading && state.alerts.isEmpty() -> item { LoadingState() }
                    state.error != null && state.alerts.isEmpty() -> item {
                        ErrorState(state.error, onRetry = { onEvent(AlertsEvent.Retry) })
                    }
                    state.none -> item { EmptyState(stringResource(Res.string.alerts_none)) }
                    else -> items(state.alerts, key = { "${it.key}-${it.id ?: 0}" }) { alert ->
                        AlertCard(alert) { onEvent(AlertsEvent.Open(alert)) }
                    }
                }
            }
        }
    }
}

// A white card with a coloured bar on the left: red for money or stock at risk, gold for work
// waiting, grey for what is only worth knowing.
@Composable
private fun AlertCard(alert: AlertRow, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val bar = when {
        alert.bad -> scheme.error
        alert.warn -> MaterialTheme.sales.gold
        else -> scheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, shape)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(modifier = Modifier.width(5.dp).fillMaxHeight().background(bar)) {}
        Column(modifier = Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                alert.title,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (alert.detail.isNotBlank()) {
                Text(
                    alert.detail,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                )
            }
        }
    }
}

private val sampleAlerts = listOf(
    AlertRow(
        key = "overdue-1-LV",
        severity = "bad",
        title = "Area 47 Superstore owes MWK 1,392,000 late",
        detail = "Lucky Ventures · 21 days past due",
        screen = "customer",
        id = 1,
    ),
    AlertRow(
        key = "plan-changes-3",
        severity = "warn",
        title = "LL 5678 has 2 changes to see",
        detail = "The warehouse changed the plan after it was sent",
        screen = "loadingPlan",
        id = 3,
    ),
    AlertRow(
        key = "short-dated",
        severity = "info",
        title = "4 batches expire within 90 days",
        detail = "Move them first",
        screen = "shortDated",
    ),
)

@Preview
@Composable
private fun AlertsPreview() {
    SalesTheme {
        AlertsScreen(
            AlertsState(loading = false, alerts = sampleAlerts, counts = AlertCounts(bad = 1, warn = 1, info = 1)),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun AlertsEmptyPreview() {
    SalesTheme { AlertsScreen(AlertsState(loading = false), onEvent = {}, onBack = {}) }
}
