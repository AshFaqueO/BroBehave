package com.brobehave.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.model.Rank
import com.brobehave.core.model.ReactionType
import com.brobehave.core.model.Reactor

private const val LOCKED_ALPHA = 0.45f

/** Bottom sheet listing who reacted, filterable by reaction type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactorsSheet(reactors: List<Reactor>?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var filter by rememberSaveable { mutableStateOf<ReactionType?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.padding(horizontal = BroTheme.spacing.md)) {
            Text(
                text = stringResource(R.string.reactors_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            when {
                reactors == null -> LoadingRow()
                reactors.isEmpty() -> Text(
                    text = stringResource(R.string.reactors_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = BroTheme.spacing.lg),
                )
                else -> {
                    val present = ReactionType.entries.filter { type -> reactors.any { it.type == type } }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = BroTheme.spacing.sm),
                    ) {
                        FilterChip(
                            selected = filter == null,
                            onClick = { filter = null },
                            label = { Text(stringResource(R.string.reactors_all, reactors.size)) },
                        )
                        present.forEach { type ->
                            FilterChip(
                                selected = filter == type,
                                onClick = { filter = type },
                                label = {
                                    Text("${type.emoji} ${reactors.count { it.type == type }}")
                                },
                            )
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = SHEET_LIST_MAX_HEIGHT)) {
                        items(
                            items = reactors.filter { filter == null || it.type == filter },
                            key = { it.userId },
                        ) { reactor ->
                            UserRow(displayName = reactor.displayName, username = reactor.username) {
                                Text(text = reactor.type.emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.padding(bottom = BroTheme.spacing.lg))
        }
    }
}

/** "All Badges": the nine ranks with their day thresholds; the current one is highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesSheet(currentStreakRank: Rank, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier
                .padding(horizontal = BroTheme.spacing.md)
                .padding(bottom = BroTheme.spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.badges_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Rank.entries.reversed().forEach { rank ->
                val isCurrent = rank == currentStreakRank
                val locked = rank.ordinal > currentStreakRank.ordinal
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                            },
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(BroTheme.spacing.sm),
                ) {
                    TierBadge(rank = rank, modifier = Modifier.alpha(if (locked) LOCKED_ALPHA else 1f))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (rank.minStreakDays == 0) {
                                stringResource(R.string.badges_threshold_start)
                            } else {
                                pluralStringResource(R.plurals.badges_threshold, rank.minStreakDays, rank.minStreakDays)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (isCurrent || locked) {
                            Text(
                                text = stringResource(
                                    if (isCurrent) R.string.badges_current else R.string.badges_locked,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
