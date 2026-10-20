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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.data.companyName
import com.salesapp.ui.theme.AccentSoft
import com.salesapp.ui.theme.Bad
import com.salesapp.ui.theme.BadSoft
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Good
import com.salesapp.ui.theme.GoodSoft
import com.salesapp.ui.theme.Ink
import com.salesapp.ui.theme.Line
import com.salesapp.ui.theme.Muted
import com.salesapp.ui.theme.companyColor

// A small coloured square followed by the full company name.
@Composable
fun CompanyTag(code: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(companyColor(code)))
        Text(companyName(code), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

// A small rounded label such as "Switched off".
@Composable
fun StatusPill(text: String, good: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        color = if (good) Good else Bad,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (good) GoodSoft else BadSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// A box with a message: blue-ish for information, red for a problem.
@Composable
fun Say(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text,
        color = if (error) Bad else Ink,
        fontSize = 15.sp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (error) BadSoft else AccentSoft)
            .padding(12.dp),
    )
}

// A text box with a big label above it.
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
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Burgundy,
                unfocusedBorderColor = Line,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
            ),
        )
        if (note != null) Text(note, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// A big button you can switch on, used to pick one or more choices.
@Composable
fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (selected) AccentSoft else Color.White)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Burgundy else Line), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            color = if (selected) Burgundy else Ink,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// A heading above a group of fields.
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(bottom = 2.dp))
}

// Choice buttons laid out one under another.
@Composable
fun ChoiceColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

