package com.salesapp.ui.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salesapp.core.channelLabel
import com.salesapp.data.Customer
import com.salesapp.resources.Res
import com.salesapp.resources.badge_cash_only
import com.salesapp.resources.badge_needs_lpo
import com.salesapp.resources.badge_off
import com.salesapp.resources.badge_switched_off
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import org.jetbrains.compose.resources.stringResource

// The small pills for a customer: channel, cash only, needs LPO, and off.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomerBadges(customer: Customer, modifier: Modifier = Modifier, offLabelLong: Boolean = false) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Badge(channelLabel(customer.channel))
        if (customer.cashOnly) Badge(stringResource(Res.string.badge_cash_only), tone = BadgeTone.On)
        if (customer.needsLpo) Badge(stringResource(Res.string.badge_needs_lpo))
        if (!customer.active) {
            Badge(stringResource(if (offLabelLong) Res.string.badge_switched_off else Res.string.badge_off), tone = BadgeTone.Off)
        }
    }
}
