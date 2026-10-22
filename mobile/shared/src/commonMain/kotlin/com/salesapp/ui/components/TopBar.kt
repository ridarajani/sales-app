package com.salesapp.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.salesapp.resources.Res
import com.salesapp.resources.back
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Canvas

// The bar at the top of every screen except Home: round "<" back button, centred title, thin line below.
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val backLabel = stringResource(Res.string.back)
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = backLabel }
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                  Box(modifier = Modifier.size(32.dp).border(1.dp, scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(width = 6.dp, height = 9.dp)) {
                        val stroke = 1.5.dp.toPx()
                        val path = Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(0f, size.height * 0.5f)
                            lineTo(size.width, size.height)
                        }
                        drawPath(path, scheme.primary, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                  }
                }
            }
            Text(
                title,
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
            )
            if (trailing != null) {
                Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
            }
        }
    }
}
