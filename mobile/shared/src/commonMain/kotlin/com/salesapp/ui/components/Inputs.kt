package com.salesapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.salesapp.core.formatIsoDate
import com.salesapp.core.isoFromUtcMillis
import com.salesapp.core.utcMillisFromIso
import com.salesapp.resources.Res
import com.salesapp.resources.cancel
import com.salesapp.resources.mwk_prefix
import com.salesapp.resources.ok
import com.salesapp.resources.search_clear
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource

// A small upper-case label above a field.
@Composable
private fun FieldLabel(label: String) {
    Text(
        label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun FieldNote(note: String?) {
    if (note != null) {
        Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
)

// A search box: magnifier on the left, a clear button when something is typed.
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(scheme.surface, shape)
                    .border(BorderStroke(1.dp, scheme.outline), shape)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    val clear = stringResource(Res.string.search_clear)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(role = Role.Button) { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = clear, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
    )
}

// A row with a label and an on/off switch.
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = scheme.primary,
                checkedThumbColor = scheme.onPrimary,
                uncheckedTrackColor = MaterialTheme.sales.surface2,
                uncheckedThumbColor = scheme.onSurfaceVariant,
                uncheckedBorderColor = scheme.outline,
            ),
        )
    }
}

// Puts a comma after every three digits while typing.
private object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val n = digits.length
        val out = StringBuilder()
        // originalToTransformed[i] = position in the shown text for a cursor after i digits.
        val toShown = IntArray(n + 1)
        for (i in 0 until n) {
            if (i > 0 && (n - i) % 3 == 0) out.append(',')
            toShown[i] = out.length
            out.append(digits[i])
        }
        toShown[n] = out.length
        val toOriginal = IntArray(out.length + 1)
        var digitsSeen = 0
        for (t in 0..out.length) {
            toOriginal[t] = digitsSeen
            if (t < out.length && out[t] != ',') digitsSeen++
        }
        return TransformedText(
            AnnotatedString(out.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, n)]
                override fun transformedToOriginal(offset: Int): Int = toOriginal[offset.coerceIn(0, out.length)]
            },
        )
    }
}

// An amount in whole kwacha, typed with the number keyboard and shown with thousands separators.
@Composable
fun MwkField(
    label: String,
    amount: Long?,
    onAmountChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        OutlinedTextField(
            value = amount?.toString() ?: "",
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(13)
                onAmountChange(digits.toLongOrNull())
            },
            singleLine = true,
            prefix = { Text(stringResource(Res.string.mwk_prefix), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            visualTransformation = ThousandsTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
        )
        FieldNote(note)
    }
}

// A text box for longer text, a few lines tall.
@Composable
fun MultilineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors(),
        )
    }
}

// 1 Jan 1900 and 31 Dec 2100 in UTC milliseconds: the dates the picker allows.
private const val DATE_RANGE_START_MILLIS = -2_208_988_800_000L
private const val DATE_RANGE_END_MILLIS = 4_133_894_400_000L

// A date shown as "20 Oct 2026". Tap it to pick another day. The value is YYYY-MM-DD.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    isoDate: String,
    onDateChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(scheme.surface, shape)
                .border(BorderStroke(1.dp, scheme.outline), shape)
                .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) { open = true }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(formatIsoDate(isoDate), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (open) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = utcMillisFromIso(isoDate)?.coerceIn(DATE_RANGE_START_MILLIS, DATE_RANGE_END_MILLIS),
            yearRange = 1900..2100,
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateChange(isoFromUtcMillis(it)) }
                        open = false
                    },
                ) { Text(stringResource(Res.string.ok), style = MaterialTheme.typography.bodyLarge) }
            },
            dismissButton = {
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { open = false }) {
                    Text(stringResource(Res.string.cancel), style = MaterialTheme.typography.bodyLarge)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
