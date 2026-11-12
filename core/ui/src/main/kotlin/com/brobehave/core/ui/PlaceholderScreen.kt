package com.brobehave.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.brobehave.core.designsystem.theme.BroTheme

/**
 * Phase 1 stand-in for a real screen: a title, a line of copy, and optional content
 * (usually a button that moves the navigation shell forward).
 */
@Composable
fun PlaceholderScreen(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.md, Alignment.CenterVertically),
        modifier = modifier
            .fillMaxSize()
            .padding(BroTheme.spacing.lg),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}
