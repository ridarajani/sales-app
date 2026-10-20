package com.salesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Gold
import com.salesapp.ui.theme.Muted

// The bar at the top of a screen: a title, a Back arrow when there is one, a gold line below.
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    sub: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.White)
            .drawBehind {
                val band = 3.dp.toPx()
                drawRect(Gold, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
            }
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("← Back", color = Burgundy, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(modifier = Modifier.padding(start = 8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Burgundy, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (sub != null) sub()
        }
        if (trailing != null) trailing()
    }
}

// A muted line of text for under a title.
@Composable
fun SubLine(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Muted, fontSize = 14.sp, modifier = modifier)
}
