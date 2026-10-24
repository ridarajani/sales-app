package com.salesapp.ui.cash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salesapp.core.formatMwk
import com.salesapp.resources.Res
import com.salesapp.resources.collect_mode_airtel
import com.salesapp.resources.collect_mode_bank
import com.salesapp.resources.collect_mode_cash
import com.salesapp.resources.delivery_photo
import com.salesapp.resources.delivery_photo_added
import com.salesapp.resources.delivery_photo_again
import com.salesapp.resources.delivery_photo_remove
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource

// Small pieces shared by the money screens.

// Cash, Bank transfer or Airtel Money in the words people see.
@Composable
fun modeLabel(mode: String): String = when (mode) {
    "bank" -> stringResource(Res.string.collect_mode_bank)
    "airtel" -> stringResource(Res.string.collect_mode_airtel)
    else -> stringResource(Res.string.collect_mode_cash)
}

// The one number that matters on a screen, in a soft box.
@Composable
fun BigMoney(label: String, amount: Double, modifier: Modifier = Modifier, warn: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (warn) sales.badSoft else sales.goldSoft)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(
            formatMwk(amount.toLong()),
            color = if (warn) sales.bad else scheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

// A label on the left and an amount on the right.
@Composable
fun MoneyRow(label: String, amount: Double, strong: Boolean = false, negative: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = if (strong) scheme.onSurface else scheme.onSurfaceVariant,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (negative) "− ${formatMwk(amount.toLong())}" else formatMwk(amount.toLong()),
            color = scheme.onSurface,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// Take a photo, or say one is there with a way to change or drop it. Shows nothing when the phone
// has no camera.
@Composable
fun PhotoField(
    label: String,
    photo: ByteArray?,
    cameraAvailable: Boolean,
    busy: Boolean,
    onTake: () -> Unit,
    onRemove: () -> Unit,
) {
    if (!cameraAvailable) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label.uppercase(), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        if (photo == null) {
            SecondaryButton(stringResource(Res.string.delivery_photo), onClick = onTake, enabled = !busy)
        } else {
            Text(stringResource(Res.string.delivery_photo_added), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(Res.string.delivery_photo_again),
                    onClick = onTake,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    stringResource(Res.string.delivery_photo_remove),
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// A picture the server sent, inside a thin frame.
@Composable
fun ServerPicture(image: ImageBitmap, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(4.dp),
    )
}
