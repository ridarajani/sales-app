package com.salesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Gold
import com.salesapp.ui.theme.Line
import com.salesapp.ui.theme.Muted

// A big square on the home screen: a bold name and a short line under it.
@Composable
fun Tile(
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .heightIn(min = 132.dp)
            .clip(shape)
            .background(if (primary) Burgundy else Color.White)
            .then(if (primary) Modifier else Modifier.border(1.dp, Line, shape))
            .drawBehind {
                if (primary) {
                    val band = 4.dp.toPx()
                    drawRect(Gold, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                color = if (primary) Color.White else Burgundy,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                sub,
                color = if (primary) Color.White.copy(alpha = 0.85f) else Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
