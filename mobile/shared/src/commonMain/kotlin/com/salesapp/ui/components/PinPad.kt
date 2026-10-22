package com.salesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.salesapp.resources.Res
import com.salesapp.resources.pin_keyboard_hint
import org.jetbrains.compose.resources.stringResource

const val PIN_LENGTH = 4

// Four dots that fill up as the PIN is typed. A hidden text field takes the digits from the phone's own number keyboard,
// which opens by itself when the screen opens.
@Composable
fun PinEntry(
    pin: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    belowDots: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicTextField(
            value = pin,
            onValueChange = { value -> onChange(value.filter { it.isDigit() }.take(PIN_LENGTH)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier
                .focusRequester(focus)
                .clickable(enabled = enabled, role = Role.Button) {
                    focus.requestFocus()
                    keyboard?.show()
                },
            decorationBox = {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(PIN_LENGTH) { index ->
                        val filled = index < pin.length
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (filled) scheme.primary else scheme.surface)
                                .border(2.dp, if (filled) scheme.primary else scheme.outline, CircleShape),
                        )
                    }
                }
            },
        )
        Text(stringResource(Res.string.pin_keyboard_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal))
        belowDots()
    }
}
