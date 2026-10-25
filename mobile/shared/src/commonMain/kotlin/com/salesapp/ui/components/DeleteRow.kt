package com.salesapp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.salesapp.resources.Res
import com.salesapp.resources.delete_action
import com.salesapp.resources.delete_confirm
import com.salesapp.resources.delete_deleting
import com.salesapp.resources.delete_text
import com.salesapp.resources.delete_title
import org.jetbrains.compose.resources.stringResource

// The one way anything is deleted: a red-outlined button that always asks first. Products,
// customers, vehicles and users all use it, so the wording and the question never drift apart.
@Composable
fun DeleteButton(
    name: String,
    asking: Boolean,
    busy: Boolean,
    onAsk: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DangerButton(
        stringResource(if (busy) Res.string.delete_deleting else Res.string.delete_action),
        onClick = onAsk,
        enabled = !busy,
        modifier = modifier,
    )
    if (asking) {
        ConfirmDialog(
            title = stringResource(Res.string.delete_title, name),
            text = stringResource(Res.string.delete_text),
            confirmLabel = stringResource(Res.string.delete_confirm),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            danger = true,
        )
    }
}
