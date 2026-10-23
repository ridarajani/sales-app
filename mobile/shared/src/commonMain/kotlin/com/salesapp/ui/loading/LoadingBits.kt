package com.salesapp.ui.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.splitPieces
import com.salesapp.data.PlanChange
import com.salesapp.data.User
import com.salesapp.data.isNewFor
import com.salesapp.resources.Res
import com.salesapp.resources.loading_carton_one
import com.salesapp.resources.loading_cartons
import com.salesapp.resources.loading_cartons_and_pieces
import com.salesapp.resources.loading_change_amount
import com.salesapp.resources.loading_change_by
import com.salesapp.resources.loading_change_reason
import com.salesapp.resources.loading_changes_from_planner
import com.salesapp.resources.loading_changes_from_warehouse
import com.salesapp.resources.loading_new_changes
import com.salesapp.resources.loading_not_sent
import com.salesapp.resources.loading_piece_one
import com.salesapp.resources.loading_pieces
import com.salesapp.resources.loading_seen
import com.salesapp.resources.loading_sent
import com.salesapp.resources.loading_status_done
import com.salesapp.resources.loading_status_loaded
import com.salesapp.resources.loading_status_out
import com.salesapp.resources.loading_status_planned
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource

// Small pieces shared by the loading plan screens.

// The plan's state as a pill: Planned or Done (gold), Loaded or On the road (green).
@Composable
fun PlanStatusBadge(status: String, modifier: Modifier = Modifier) {
    when (status) {
        "planned" -> Badge(stringResource(Res.string.loading_status_planned), modifier, tone = BadgeTone.Role)
        "loaded" -> Badge(stringResource(Res.string.loading_status_loaded), modifier, tone = BadgeTone.On)
        "out" -> Badge(stringResource(Res.string.loading_status_out), modifier, tone = BadgeTone.On)
        "done" -> Badge(stringResource(Res.string.loading_status_done), modifier, tone = BadgeTone.Role)
        else -> Badge(status, modifier, tone = BadgeTone.Role)
    }
}

// Whether the planner has sent the plan to the warehouse yet.
@Composable
fun SentBadge(sent: Boolean, modifier: Modifier = Modifier) {
    if (sent) {
        Badge(stringResource(Res.string.loading_sent), modifier, tone = BadgeTone.On)
    } else {
        Badge(stringResource(Res.string.loading_not_sent), modifier, tone = BadgeTone.Off)
    }
}

@Composable
fun NewChangesBadge(modifier: Modifier = Modifier) {
    Badge(stringResource(Res.string.loading_new_changes), modifier, tone = BadgeTone.Off)
}

// "3 cartons", "3 cartons + 4 pieces" or "4 pieces".
@Composable
fun cartonsText(cartons: Int, loosePieces: Int): String {
    val cartonPart = if (cartons == 1) stringResource(Res.string.loading_carton_one) else stringResource(Res.string.loading_cartons, cartons)
    val piecePart = if (loosePieces == 1) stringResource(Res.string.loading_piece_one) else stringResource(Res.string.loading_pieces, loosePieces)
    return when {
        loosePieces == 0 -> cartonPart
        cartons == 0 -> piecePart
        else -> stringResource(Res.string.loading_cartons_and_pieces, cartonPart, piecePart)
    }
}

// A raw number of pieces in cartons of piecesPerCarton, as cartonsText words.
@Composable
fun splitText(pieces: Int, piecesPerCarton: Int): String {
    val (cartons, loose) = splitPieces(pieces, piecesPerCarton)
    return cartonsText(cartons, loose)
}

// The changes from the other side this user has not seen yet, with an "OK, seen" button.
// Shows nothing when there are none.
@Composable
fun ChangesBanner(
    changes: List<PlanChange>,
    user: User?,
    onSeen: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    if (user == null) return
    val fresh = changes.filter { !it.seen && user.isNewFor(it.byWarehouse) }
    if (fresh.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, sales.bad, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Admin can see both sides at once: warehouse changes first, then the planner's.
        fresh.groupBy { it.byWarehouse }.entries.sortedByDescending { it.key }.forEach { (byWarehouse, rows) ->
            Text(
                stringResource(if (byWarehouse) Res.string.loading_changes_from_warehouse else Res.string.loading_changes_from_planner),
                color = sales.bad,
                style = MaterialTheme.typography.titleSmall,
            )
            rows.forEach { change -> ChangeRow(change) }
        }
        SecondaryButton(stringResource(Res.string.loading_seen), onClick = onSeen, enabled = !busy)
    }
}

@Composable
private fun ChangeRow(change: PlanChange) {
    val scheme = MaterialTheme.colorScheme
    val what = listOfNotNull(change.product, change.orderNumber, change.customerName).filter { it.isNotBlank() }.joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(
                Res.string.loading_change_amount,
                what,
                splitText(change.before, change.piecesPerCarton),
                splitText(change.after, change.piecesPerCarton),
            ),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (change.reason.isNotBlank()) {
            Text(stringResource(Res.string.loading_change_reason, change.reason), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(Res.string.loading_change_by, change.by, formatIsoDate(localIsoDateFromUtcTimestamp(change.at))),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Preview
@Composable
private fun ChangesBannerPreview() {
    SalesTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanStatusBadge("planned")
            SentBadge(sent = true)
            NewChangesBadge()
            ChangesBanner(
                changes = listOf(
                    PlanChange(
                        id = 1, byWarehouse = true, by = "Peter Phiri", at = "2026-10-23T10:00:00.000Z", productId = 1,
                        product = "Lucky Milk 500ml", piecesPerCarton = 12, before = 120, after = 100, reason = "Truck is full",
                    ),
                ),
                user = User(id = 1, name = "Grace", phone = "", role = "sales_manager"),
                onSeen = {},
                busy = false,
            )
        }
    }
}
