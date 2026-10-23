package com.salesapp.ui.orders

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.salesapp.data.OrderCompanyRef
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.order_invoice_plain
import com.salesapp.resources.order_invoice_vat
import com.salesapp.resources.order_short
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
