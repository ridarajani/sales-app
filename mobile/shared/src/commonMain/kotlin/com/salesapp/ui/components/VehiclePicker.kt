package com.salesapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.salesapp.data.Vehicle
import com.salesapp.resources.Res
import com.salesapp.resources.vehicle_show_all
import com.salesapp.resources.vehicle_show_fewer
import org.jetbrains.compose.resources.stringResource

// Everywhere a vehicle is picked. Client, 24 Oct: each person marks the vehicles they do not use,
// so only the ones in use are offered, with the rest one tap away. The one already chosen always
// shows, even if it is marked not in use, so a plan made earlier still reads right.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehiclePicker(
    vehicles: List<Vehicle>,
    chosenId: Int?,
    onChoose: (Int) -> Unit,
    enabled: Boolean = true,
    label: @Composable (Vehicle) -> String = { it.plate },
    modifier: Modifier = Modifier,
) {
    var showAll by remember { mutableStateOf(false) }
    val inUse = vehicles.filter { it.inUse || it.id == chosenId }
    val shown = if (showAll) vehicles else inUse
    val hidden = vehicles.size - inUse.size
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { vehicle ->
                ChoiceButton(
                    text = label(vehicle),
                    selected = chosenId == vehicle.id,
                    onClick = { onChoose(vehicle.id) },
                    enabled = enabled,
                )
            }
        }
        // Only worth offering when something is actually hidden.
        if (hidden > 0) {
            Text(
                stringResource(if (showAll) Res.string.vehicle_show_fewer else Res.string.vehicle_show_all),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clickable(enabled = enabled, role = Role.Button) { showAll = !showAll },
            )
        }
    }
}
