package com.brobehave.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroBehaveTheme
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.designsystem.theme.style
import com.brobehave.core.model.Rank

/**
 * Rank pill: the rank's color, its name, and a "Verified ✓" mark when the user has
 * automatic detection on. Rank is always conveyed by name as well as color.
 */
@Composable
fun TierBadge(rank: Rank, modifier: Modifier = Modifier, verified: Boolean = false) {
    val style = rank.style
    val name = stringResource(style.nameRes)
    val description = stringResource(
        if (verified) R.string.tier_badge_description_verified else R.string.tier_badge_description,
        name,
    )
    Surface(
        color = style.color,
        contentColor = style.onColor,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier.padding(horizontal = BroTheme.spacing.md, vertical = BroTheme.spacing.sm),
        ) {
            // Placeholder for the illustrated rank avatar from the design handoff.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .background(style.onColor, CircleShape),
            ) {
                Text(
                    text = name.take(1),
                    color = style.color,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                )
            }
            Text(text = name, style = MaterialTheme.typography.labelLarge)
            if (verified) {
                Text(
                    text = stringResource(R.string.tier_badge_verified) + " ✓",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Preview
@Composable
private fun TierBadgePreview() {
    BroBehaveTheme(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Rank.entries.forEach { TierBadge(rank = it, verified = it.ordinal % 2 == 0) }
        }
    }
}
