package com.salesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Ink
import com.salesapp.ui.theme.Line

const val PIN_LENGTH = 4

// Four dots that fill up as the PIN is typed, and a big number pad under them.
@Composable
fun PinEntry(
    pin: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    belowDots: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.height(28.dp)) {
            repeat(PIN_LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (filled) Burgundy else Color.White)
                        .border(2.dp, if (filled) Burgundy else Line, CircleShape),
                )
            }
        }
        belowDots()
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.weight(1f).height(64.dp))
                    } else {
                        PinKey(
                            key = key,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (key == "⌫") {
                                    onChange(pin.dropLast(1))
                                } else if (pin.length < PIN_LENGTH) {
                                    onChange(pin + key)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// One key on the number pad.
@Composable
private fun PinKey(key: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Ink else Ink.copy(alpha = 0.35f),
        )
    }
}
