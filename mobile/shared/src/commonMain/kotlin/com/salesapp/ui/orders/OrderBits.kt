package com.salesapp.ui.orders

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.salesapp.data.OrderCompanyRef
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.order_invoice_plain
import com.salesapp.resources.order_invoice_vat
import com.salesapp.resources.order_short
import com.salesapp.resources.order_stage_on_vehicle
import com.salesapp.resources.order_stage_open
import com.salesapp.resources.order_stage_part_delivered
import com.salesapp.resources.order_stage_planned
import com.salesapp.resources.order_status_cancelled
import com.salesapp.resources.order_status_done
import com.salesapp.resources.order_status_hold
import com.salesapp.resources.order_status_open
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import org.jetbrains.compose.resources.stringResource
import kotlin.jvm.JvmName

// Small pieces shared by the order screens.

// The order's state as a pill: Open (green), On hold or Done (gold), Cancelled (red).
@Composable
fun OrderStatusBadge(status: String, modifier: Modifier = Modifier) {
    when (status) {
        "open" -> Badge(stringResource(Res.string.order_status_open), modifier, tone = BadgeTone.On)
        "hold" -> Badge(stringResource(Res.string.order_status_hold), modifier, tone = BadgeTone.Role)
        "done" -> Badge(stringResource(Res.string.order_status_done), modifier, tone = BadgeTone.Role)
        "cancelled" -> Badge(stringResource(Res.string.order_status_cancelled), modifier, tone = BadgeTone.Off)
        else -> Badge(status, modifier, tone = BadgeTone.Role)
    }
}

// Where an order is in loading: Open (green), Planned, On the vehicle or Part delivered (gold).
// Orders that are not open carry their status as the stage, so those fall back to the status pill.
@Composable
fun OrderStageBadge(stage: String, modifier: Modifier = Modifier) {
    when (stage) {
        "open" -> Badge(stringResource(Res.string.order_stage_open), modifier, tone = BadgeTone.On)
        "planned" -> Badge(stringResource(Res.string.order_stage_planned), modifier, tone = BadgeTone.Role)
        "on_vehicle" -> Badge(stringResource(Res.string.order_stage_on_vehicle), modifier, tone = BadgeTone.Role)
        "part_delivered" -> Badge(stringResource(Res.string.order_stage_part_delivered), modifier, tone = BadgeTone.Role)
        else -> OrderStatusBadge(stage, modifier)
    }
}

// An open order shows its stage; on hold, done and cancelled show the status.
@Composable
fun OrderStateBadge(status: String, stage: String, modifier: Modifier = Modifier) {
    if (status == "open") OrderStageBadge(stage, modifier) else OrderStatusBadge(status, modifier)
}

// Shown when part of an order has no stock set aside. Never show it to a salesman.
@Composable
fun ShortBadge(modifier: Modifier = Modifier) {
    Badge(stringResource(Res.string.order_short), modifier, tone = BadgeTone.Off)
}

// "VAT invoice" or "Plain invoice".
@Composable
fun invoiceTypeLabel(type: String): String = when (type) {
    "plain" -> stringResource(Res.string.order_invoice_plain)
    else -> stringResource(Res.string.order_invoice_vat)
}

// "Lucky Ventures · Zoona Trading".
fun orderCompaniesLabel(codes: List<String>): String = codes.joinToString(" · ") { companyName(it) }

@JvmName("orderCompaniesLabelRefs")
fun orderCompaniesLabel(companies: List<OrderCompanyRef>): String = orderCompaniesLabel(companies.map { it.code })
