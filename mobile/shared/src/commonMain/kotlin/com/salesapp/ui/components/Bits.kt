package com.salesapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.salesapp.data.companyName
import com.salesapp.ui.theme.companyColor
import com.salesapp.ui.theme.sales

// A round picture with a person's initials.
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, tone: Color = Color.Unspecified) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    val primary = MaterialTheme.colorScheme.primary
    val fill = if (tone == Color.Unspecified) MaterialTheme.sales.accentSoft else tone
    val onPrimary = fill == primary
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = if (onPrimary) MaterialTheme.colorScheme.onPrimary else primary,
            style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

enum class BadgeTone { Role, Off, On }

// A small pill: a job, "Off" or "Can sign in".
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, tone: BadgeTone = BadgeTone.Role) {
    val sales = MaterialTheme.sales
    val fill = when (tone) {
        BadgeTone.Role -> sales.goldSoft
        BadgeTone.Off -> sales.badSoft
        BadgeTone.On -> sales.goodSoft
    }
    val ink = when (tone) {
        BadgeTone.Role -> MaterialTheme.colorScheme.onSurface
        BadgeTone.Off -> sales.bad
        BadgeTone.On -> sales.good
    }
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(fill).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// A small coloured dot followed by the company name.
@Composable
fun CompanyDot(code: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(companyColor(code)))
        Text(companyName(code), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), modifier = Modifier.padding(start = 6.dp))
    }
}

// A box with a message: blue-ish for information, red for a problem.
@Composable
fun Say(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val sales = MaterialTheme.sales
    Text(
        text,
        color = if (error) sales.bad else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (error) sales.badSoft else sales.accentSoft)
            .padding(12.dp),
    )
}

// A text box with a small label above it.
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    note: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label.uppercase(), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.primary,
                unfocusedBorderColor = scheme.outline,
                focusedContainerColor = scheme.surface,
                unfocusedContainerColor = scheme.surface,
            ),
        )
        if (note != null) Text(note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

// A button you can switch on, used to pick one or more choices.
@Composable
fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.sales.accentSoft else scheme.surface)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) scheme.primary else scheme.outline), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = if (selected) scheme.primary else scheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// A small upper-case heading above a group of fields or tiles.
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, modifier = modifier.padding(bottom = 2.dp))
}

// Choice buttons laid out one under another.
@Composable
fun ChoiceColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

