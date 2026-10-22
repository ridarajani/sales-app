package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.salesapp.core.formatIsoDate
import com.salesapp.core.todayIsoDate
import com.salesapp.core.utcMillisFromIso
import com.salesapp.data.StockProduct
import com.salesapp.resources.Res
import com.salesapp.resources.stock_expires
import com.salesapp.resources.stock_pill_expired
import com.salesapp.resources.stock_pill_near
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import org.jetbrains.compose.resources.stringResource

internal const val NEAR_EXPIRY_DAYS = 90
private const val MILLIS_PER_DAY = 86_400_000L

// Days from today to the date; below zero means it has passed. Null if the date cannot be read.
internal fun daysUntil(iso: String): Int? {
    val date = utcMillisFromIso(iso) ?: return null
    val today = utcMillisFromIso(todayIsoDate()) ?: return null
    return ((date - today) / MILLIS_PER_DAY).toInt()
}

internal fun productTitle(brand: String, name: String, packSize: String): String =
    listOf(brand, name, packSize).filter { it.isNotBlank() }.joinToString(" ").trim()

internal fun StockProduct.title(): String = productTitle(brand, name, packSize)

// "Expires 12 Mar 2027" with a red "Expired" or gold "Near expiry" pill when it matters.
@Composable
internal fun ExpiryLine(expiry: String, modifier: Modifier = Modifier) {
    val days = daysUntil(expiry)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.stock_expires, formatIsoDate(expiry)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            days == null -> {}
            days < 0 -> Badge(stringResource(Res.string.stock_pill_expired), tone = BadgeTone.Off)
            days <= NEAR_EXPIRY_DAYS -> Badge(stringResource(Res.string.stock_pill_near), tone = BadgeTone.Role)
        }
    }
}
