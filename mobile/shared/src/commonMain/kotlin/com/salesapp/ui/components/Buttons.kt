package com.salesapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(12.dp)

// The main button on a screen: solid burgundy, full width.
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(ButtonShape)
            .background(if (enabled) scheme.primary else scheme.primary.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = scheme.onPrimary, style = MaterialTheme.typography.titleSmall)
    }
}

// A quieter button with a thin outline, for second choices.
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButtonBox(text, onClick, modifier, enabled, borderColor = MaterialTheme.colorScheme.outline, textColor = MaterialTheme.colorScheme.onSurface)
}

// An outlined red button for things that switch something off or sign out.
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButtonBox(text, onClick, modifier, enabled, borderColor = MaterialTheme.colorScheme.error, textColor = MaterialTheme.colorScheme.error)
}

@Composable
private fun OutlinedButtonBox(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    borderColor: Color,
    textColor: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(ButtonShape)
            .background(scheme.surface)
            .border(BorderStroke(1.dp, if (enabled) borderColor else scheme.outline), ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
