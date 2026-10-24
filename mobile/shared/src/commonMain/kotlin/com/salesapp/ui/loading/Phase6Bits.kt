package com.salesapp.ui.loading

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.MadeInvoice
import com.salesapp.data.PlanDelivery
import com.salesapp.data.PlanDropLine
import com.salesapp.data.PlanInvoice
import com.salesapp.resources.Res
import com.salesapp.resources.delivery_confirmed_by
import com.salesapp.resources.delivery_gate_pass_doc
import com.salesapp.resources.delivery_image_failed
import com.salesapp.resources.delivery_line_all
import com.salesapp.resources.delivery_line_reason
import com.salesapp.resources.delivery_line_taken
import com.salesapp.resources.delivery_note_doc
import com.salesapp.resources.delivery_photo_title
import com.salesapp.resources.delivery_received_from
import com.salesapp.resources.delivery_signature
import com.salesapp.resources.invoice_list_plain
import com.salesapp.resources.invoice_list_vat
import com.salesapp.resources.invoice_made_many
import com.salesapp.resources.invoice_made_one
import com.salesapp.resources.invoice_made_row
import com.salesapp.resources.invoice_make
import com.salesapp.resources.invoice_make_help
import com.salesapp.resources.invoice_making
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource

// Phase 6 pieces shared by the plan screens: making the invoices, the vehicle's documents, the
// invoices on a drop and a drop that is already confirmed.

// "Make invoices", and what the last run made.
@Composable
fun MakeInvoices(
    canMake: Boolean,
    busy: Boolean,
    made: List<MadeInvoice>,
    onMake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!canMake && made.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (made.isNotEmpty()) {
            val heading = if (made.size == 1) {
                stringResource(Res.string.invoice_made_one)
            } else {
                stringResource(Res.string.invoice_made_many, made.size)
            }
            val rows = made.map { stringResource(Res.string.invoice_made_row, it.number, formatMwk(it.total.toLong())) }
            Say((listOf(heading) + rows).joinToString("\n"))
        }
        if (canMake) {
            Text(
                stringResource(Res.string.invoice_make_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            PrimaryButton(
                stringResource(if (busy) Res.string.invoice_making else Res.string.invoice_make),
                onClick = onMake,
                enabled = !busy,
            )
        }
    }
}

// The delivery note and the gate pass, side by side because they belong together.
@Composable
fun VehicleDocuments(
    busy: Boolean,
    onDeliveryNote: () -> Unit,
    onGatePass: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(
            stringResource(Res.string.delivery_note_doc),
            onClick = onDeliveryNote,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            stringResource(Res.string.delivery_gate_pass_doc),
            onClick = onGatePass,
            enabled = !busy,
            modifier = Modifier.weight(1f),
        )
    }
}

// The invoices made for one shop, each with its type and total.
@Composable
fun DropInvoices(invoices: List<PlanInvoice>, onOpen: ((Int) -> Unit)? = null) {
    if (invoices.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        invoices.forEach { invoice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .then(if (onOpen != null) Modifier.clickable(role = Role.Button) { onOpen(invoice.id) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    invoice.number,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    stringResource(if (invoice.invoiceType == "vat") Res.string.invoice_list_vat else Res.string.invoice_list_plain),
                    tone = BadgeTone.Role,
                )
                Text(
                    formatMwk(invoice.total.toLong()),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// A drop the driver has already confirmed: who signed for it, when, the signature, the photo and
// what was actually taken.
@Composable
fun DeliveredDetails(
    delivery: PlanDelivery,
    lines: List<PlanDropLine>,
    perCarton: Map<Int, Int>,
    signature: ImageBitmap?,
    photo: ImageBitmap?,
    imagesFailed: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.delivery_received_from, delivery.receivedBy),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (delivery.confirmedAt.isNotBlank()) {
            Text(
                formatIsoDate(localIsoDateFromUtcTimestamp(delivery.confirmedAt)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (delivery.confirmedBy.isNotBlank()) {
            Text(
                stringResource(Res.string.delivery_confirmed_by, delivery.confirmedBy),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
        }
        lines.filter { it.loaded > 0 }.forEach { line ->
            val size = perCarton[line.productId] ?: 1
            val name = listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" ")
            val amount = if (line.delivered == line.loaded) {
                stringResource(Res.string.delivery_line_all, splitText(line.delivered, size))
            } else {
                stringResource(Res.string.delivery_line_taken, splitText(line.delivered, size), splitText(line.loaded, size))
            }
            val reason = line.shortReason?.takeIf { it.isNotBlank() }
            Text(
                if (reason != null) stringResource(Res.string.delivery_line_reason, "$name: $amount", reason) else "$name: $amount",
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (signature != null) {
            SectionLabel(stringResource(Res.string.delivery_signature))
            Picture(signature)
        }
        if (photo != null) {
            SectionLabel(stringResource(Res.string.delivery_photo_title))
            Picture(photo)
        }
        if (imagesFailed && signature == null) {
            Text(stringResource(Res.string.delivery_image_failed), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun Picture(image: ImageBitmap) {
    val shape = RoundedCornerShape(12.dp)
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(4.dp),
    )
}
