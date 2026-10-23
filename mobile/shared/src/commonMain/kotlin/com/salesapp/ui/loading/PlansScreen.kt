package com.salesapp.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.LoadingPlanListItem
import com.salesapp.resources.Res
import com.salesapp.resources.loading_list_delivery_note
import com.salesapp.resources.loading_list_driver
import com.salesapp.resources.loading_list_empty
import com.salesapp.resources.loading_list_empty_driver
import com.salesapp.resources.loading_list_new
import com.salesapp.resources.loading_list_no_driver
import com.salesapp.resources.loading_list_no_match
import com.salesapp.resources.loading_list_no_vehicle
import com.salesapp.resources.loading_list_shop_one
import com.salesapp.resources.loading_list_shops
import com.salesapp.resources.loading_list_title
import com.salesapp.resources.loading_list_title_driver
import com.salesapp.resources.loading_status_done
import com.salesapp.resources.loading_status_loaded
import com.salesapp.resources.loading_status_out
import com.salesapp.resources.loading_status_planned
import com.salesapp.resources.number_no_access
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PlansPage(
    onBack: () -> Unit,
    onOpen: (Any) -> Unit,
    onNewPlan: () -> Unit,
    viewModel: PlansViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlansEffect.Open -> onOpen(effect.route)
                PlansEffect.NewPlan -> onNewPlan()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(PlansEvent.Refresh)
        onPauseOrDispose {}
    }
    PlansScreen(state, viewModel::onEvent, onBack)
}

// Loading plans, newest first, with status chips. Planners get a "New plan" button; drivers see their trips.
@Composable
fun PlansScreen(state: PlansState, onEvent: (PlansEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(if (state.isDriver) Res.string.loading_list_title_driver else Res.string.loading_list_title),
            onBack = onBack,
        )
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        FilterChips(
            options = listOf(
                "planned" to stringResource(Res.string.loading_status_planned),
                "loaded" to stringResource(Res.string.loading_status_loaded),
                "out" to stringResource(Res.string.loading_status_out),
                "done" to stringResource(Res.string.loading_status_done),
            ),
            selected = state.status,
            onSelect = { onEvent(PlansEvent.StatusChosen(it)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.plans.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(PlansEvent.Retry) }) }
                state.plans.isEmpty() -> item {
                    EmptyState(
                        stringResource(
                            when {
                                state.status != null -> Res.string.loading_list_no_match
                                state.isDriver -> Res.string.loading_list_empty_driver
                                else -> Res.string.loading_list_empty
                            },
                        ),
                    )
                }
                else -> items(state.plans, key = { it.id }) { plan ->
                    PlanRow(plan, showChanges = state.seesChanges, onClick = { onEvent(PlansEvent.Open(plan)) })
                }
            }
        }
        if (state.canPlan) {
            PrimaryButton(
                stringResource(Res.string.loading_list_new),
                onClick = { onEvent(PlansEvent.NewPlan) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanRow(plan: LoadingPlanListItem, showChanges: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plan.number,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PlanStatusBadge(plan.status, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                plan.vehiclePlate?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.loading_list_no_vehicle),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                plan.driverName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.loading_list_driver, it) }
                    ?: stringResource(Res.string.loading_list_no_driver),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val amount = cartonsText(plan.cartons, plan.loosePieces)
            Text(
                if (plan.customers == 1) {
                    stringResource(Res.string.loading_list_shop_one, amount)
                } else {
                    stringResource(Res.string.loading_list_shops, plan.customers, amount)
                },
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val showSent = plan.status == "planned"
            val showNew = showChanges && plan.newChanges > 0
            if (showSent || showNew) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showSent) SentBadge(plan.sent)
                    if (showNew) NewChangesBadge()
                }
            }
            val note = plan.deliveryNoteNo
            if ((plan.status == "loaded" || plan.status == "out") && !note.isNullOrBlank()) {
                Text(
                    stringResource(Res.string.loading_list_delivery_note, note),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

private val samplePlans = listOf(
    LoadingPlanListItem(
        id = 3, number = "LP-000014", facilityCode = "F1", status = "planned", sent = false,
        vehiclePlate = "LL 4521", driverName = "Kondwani Banda", createdAt = "2026-10-23T07:45:00Z",
        customers = 5, pieces = 1460, cartons = 62, loosePieces = 4, newChanges = 2,
    ),
    LoadingPlanListItem(
        id = 2, number = "LP-000013", facilityCode = "F1", status = "planned", sent = true,
        createdAt = "2026-10-22T13:10:00Z", customers = 1, pieces = 240, cartons = 10,
    ),
    LoadingPlanListItem(
        id = 1, number = "LP-000012", facilityCode = "F2", status = "out", sent = true,
        vehiclePlate = "BT 9902", driverName = "Chisomo Phiri", deliveryNoteNo = "DN-000031", gatePassNo = "GP-000031",
        createdAt = "2026-10-21T09:00:00Z", customers = 8, pieces = 2100, cartons = 88, loosePieces = 0,
    ),
)

@Preview
@Composable
private fun PlansPreview() {
    SalesTheme {
        PlansScreen(
            state = PlansState(loading = false, canPlan = true, seesChanges = true, plans = samplePlans),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlansDriverEmptyPreview() {
    SalesTheme {
        PlansScreen(state = PlansState(loading = false, isDriver = true), onEvent = {}, onBack = {})
    }
}
