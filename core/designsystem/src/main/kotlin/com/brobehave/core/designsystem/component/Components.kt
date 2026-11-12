package com.brobehave.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brobehave.core.designsystem.theme.BroTheme

/** The main call to action. Shows a spinner and ignores clicks while [loading]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = BroTheme.spacing.touchTarget),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Centered message for empty lists, with an optional call to action. */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxWidth()
            .padding(BroTheme.spacing.lg),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            PrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = BroTheme.spacing.md),
            )
        }
    }
}

/** Kind confirmation dialog used for "I relapsed", "I slipped", sign-out and deletion. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.md)) {
                Text(message)
                content?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        modifier = modifier,
    )
}
