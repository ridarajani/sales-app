package com.salesapp.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.TripListItem
import com.salesapp.resources.Res
import com.salesapp.resources.trips_closed
import com.salesapp.resources.trips_empty
import com.salesapp.resources.trips_help
import com.salesapp.resources.trips_leader
import com.salesapp.resources.trips_minibus
import com.salesapp.resources.trips_no_access
import com.salesapp.resources.trips_no_match
import com.salesapp.resources.trips_on_road
import com.salesapp.resources.trips_plan
import com.salesapp.resources.trips_route
import com.salesapp.resources.trips_sold_of
import com.salesapp.resources.trips_status_closed
import com.salesapp.resources.trips_status_out
import com.salesapp.resources.trips_status_planned
import com.salesapp.resources.trips_title
import com.salesapp.resources.trips_to_load
import com.salesapp.resources.trips_upcountry
import com.salesapp.resources.trips_waiting
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.loading.cartonsText
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TripsPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onPlan: () -> Unit,
    viewModel: TripsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(TripsEvent.Refresh)
        onPauseOrDispose {}
    }
    TripsScreen(state, viewModel::onEvent, onBack, onOpen, onPlan)
}

// Minibus routes and upcountry trips: the ones on the road first, then those waiting to load.
@Composable
fun TripsScreen(
    state: TripsState,
    onEvent: (TripsEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onPlan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.trips_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.trips_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        FilterChips(
            options = listOf(
                "out" to stringResource(Res.string.trips_status_out),
                "planned" to stringResource(Res.string.trips_status_planned),
                "closed" to stringResource(Res.string.trips_status_closed),
            ),
            selected = state.status,
            onSelect = { onEvent(TripsEvent.StatusChosen(it)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.trips.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(TripsEvent.Retry) }) }
                state.trips.isEmpty() -> item {
                    EmptyState(stringResource(if (state.status != null) Res.string.trips_no_match else Res.string.trips_empty))
                }
                else -> {
                    item {
                        Text(
                            stringResource(Res.string.trips_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(state.trips, key = { it.id }) { trip -> TripRow(trip, onClick = { onOpen(trip.id) }) }
                }
            }
        }
        if (state.canPlan) {
            PrimaryButton(stringResource(Res.string.trips_plan), onClick = onPlan, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun TripRow(trip: TripListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        Res.string.trips_route,
                        stringResource(if (trip.isMinibus) Res.string.trips_minibus else Res.string.trips_upcountry),
                        trip.route,
                    ),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    stringResource(
                        when {
                            trip.onTheRoad -> Res.string.trips_on_road
                            trip.closed -> Res.string.trips_closed
                            else -> Res.string.trips_waiting
                        },
                    ),
                    tone = when {
                        trip.onTheRoad -> BadgeTone.On
                        trip.closed -> BadgeTone.Role
                        else -> BadgeTone.Off
                    },
                )
            }
            Text(trip.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            Text(formatIsoDate(trip.tripDate), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(Res.string.trips_leader, trip.leaderName),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (trip.waitingToLoad) {
                    stringResource(Res.string.trips_to_load, cartonsText(trip.cartons, trip.loosePieces))
                } else {
                    stringResource(Res.string.trips_sold_of, trip.soldCartons, trip.cartons)
                },
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
            // The warehouse gets no money from the server at all.
            trip.salesTotal?.takeIf { !trip.waitingToLoad }?.let {
                Text(
                    formatMwk(it.toLong()),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val sampleTrips = listOf(
    TripListItem(
        id = 2, number = "TRP-000002", kind = "upcountry", facilityCode = "F1", tripDate = "2026-10-25",
        route = "Salima and Nkhotakota", status = "out", vehiclePlate = "LL 5678", leaderName = "Chimwemwe Banda",
        staff = "Chimwemwe, T. Mwale", cartons = 15, soldCartons = 6, salesTotal = 345_000.0, salesCount = 3,
    ),
    TripListItem(
        id = 1, number = "TRP-000001", kind = "minibus", facilityCode = "F1", tripDate = "2026-10-24",
        route = "Area 25 and Kauma", status = "planned", leaderName = "Mr. Abbas", cartons = 20, loosePieces = 4,
    ),
)

@Preview
@Composable
private fun TripsPreview() {
    SalesTheme {
        TripsScreen(TripsState(loading = false, canPlan = true, trips = sampleTrips), onEvent = {}, onBack = {}, onOpen = {}, onPlan = {})
    }
}

@Preview
@Composable
private fun TripsWarehousePreview() {
    SalesTheme {
        TripsScreen(
            // The warehouse gets no sales total from the server, so none is shown.
            TripsState(loading = false, showsMoney = false, trips = sampleTrips.map { it.copy(salesTotal = null) }),
            onEvent = {},
            onBack = {},
            onOpen = {},
            onPlan = {},
        )
    }
}

@Preview
@Composable
private fun TripsEmptyPreview() {
    SalesTheme {
        TripsScreen(TripsState(loading = false, canPlan = true), onEvent = {}, onBack = {}, onOpen = {}, onPlan = {})
    }
}
