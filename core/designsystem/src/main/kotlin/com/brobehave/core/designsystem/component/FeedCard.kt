package com.brobehave.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroTheme
import com.brobehave.core.designsystem.theme.style
import com.brobehave.core.model.EventType
import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.Rank
import com.brobehave.core.model.ReactionType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

val ReactionType.labelRes: Int
    get() = when (this) {
        ReactionType.HAHA -> R.string.reaction_haha
        ReactionType.IMPRESSIVE -> R.string.reaction_impressive
        ReactionType.FIRE -> R.string.reaction_fire
        ReactionType.RESPECT -> R.string.reaction_respect
        ReactionType.STRONG -> R.string.reaction_strong
    }

private fun FeedPost.payloadString(key: String): String? = (payload[key] as? JsonPrimitive)?.takeIf {
    it.isString
}?.content

private fun FeedPost.payloadInt(key: String): Int? = (payload[key] as? JsonPrimitive)?.intOrNull

/** The one-line story of a post, in friendly banter. */
@Composable
fun postHeadline(post: FeedPost): String {
    val name = post.displayName ?: post.username ?: stringResource(R.string.post_someone)
    return when (post.type) {
        EventType.STREAK_STARTED -> stringResource(R.string.post_streak_started, name)
        EventType.RELAPSE -> stringResource(R.string.post_relapse, name)
        EventType.MILESTONE -> {
            val rank = post.payloadString("rank")?.let(Rank::fromKey) ?: Rank.NOOB
            val days = post.payloadInt("streak_days") ?: rank.minStreakDays
            pluralStringResource(R.plurals.post_milestone, days, name, days, stringResource(rank.style.nameRes))
        }
        EventType.TIER_UP, EventType.TIER_DOWN -> {
            val to = post.payloadString("to")?.let(Rank::fromKey) ?: Rank.CLOWN
            val template = if (post.type == EventType.TIER_UP) R.string.post_tier_up else R.string.post_tier_down
            stringResource(template, name, stringResource(to.style.nameRes))
        }
    }
}

/**
 * One feed post: who, what happened, and the reactions.
 *
 * @param timeLabel already-formatted relative time ("2h").
 * @param canReact false on the viewer's own posts.
 */
@Composable
fun FeedCard(
    post: FeedPost,
    timeLabel: String,
    onReact: (ReactionType?) -> Unit,
    onOpenReactors: () -> Unit,
    modifier: Modifier = Modifier,
    canReact: Boolean = true,
    onOpenProfile: (() -> Unit)? = null,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier.padding(BroTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpenProfile != null) {
                            Modifier.clickable(role = Role.Button, onClick = onOpenProfile)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Avatar(name = post.displayName ?: post.username)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = post.displayName ?: post.username.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                post.tier?.let { TierBadge(rank = it, verified = post.tierVerified) }
            }
            Text(text = postHeadline(post), style = MaterialTheme.typography.bodyLarge)
            ReactionBar(post = post, canReact = canReact, onReact = onReact, onOpenReactors = onOpenReactors)
        }
    }
}

/**
 * The react button (tap = quick 🤯, tap again = remove, long-press = picker) plus the
 * top three reactions and the total.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReactionBar(
    post: FeedPost,
    canReact: Boolean,
    onReact: (ReactionType?) -> Unit,
    onOpenReactors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable(post.id) { mutableStateOf(false) }
    val mine = post.myReaction

    Column(modifier) {
        AnimatedVisibility(
            visible = pickerOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ReactionPicker(
                selected = mine,
                allowHaha = post.allowHaha,
                onPick = { picked ->
                    pickerOpen = false
                    onReact(if (picked == mine) null else picked)
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (canReact) {
                val label = if (mine == null) {
                    stringResource(R.string.reaction_react)
                } else {
                    stringResource(R.string.reaction_remove, stringResource(mine.labelRes))
                }
                val hint = stringResource(R.string.reaction_react_hint)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
                    modifier = Modifier
                        .heightIn(min = BroTheme.spacing.touchTarget)
                        .background(
                            color = if (mine != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = label,
                            onLongClickLabel = hint,
                            onLongClick = { pickerOpen = !pickerOpen },
                            onClick = {
                                pickerOpen = false
                                onReact(if (mine == null) ReactionType.IMPRESSIVE else null)
                            },
                        )
                        .padding(horizontal = BroTheme.spacing.md)
                        .semantics { selected = mine != null },
                ) {
                    BouncingEmoji(reaction = mine ?: ReactionType.IMPRESSIVE, bounce = mine == ReactionType.HAHA)
                    Text(
                        text = stringResource(mine?.labelRes ?: R.string.reaction_react),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (post.reactionTotal > 0) {
                val summary = pluralStringResource(R.plurals.reaction_count, post.reactionTotal, post.reactionTotal)
                val showAll = stringResource(R.string.reaction_show_all)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
                    modifier = Modifier
                        .heightIn(min = BroTheme.spacing.touchTarget)
                        .clickable(role = Role.Button, onClickLabel = showAll, onClick = onOpenReactors)
                        .padding(horizontal = BroTheme.spacing.sm)
                        .clearAndSetSemantics {
                            contentDescription = summary
                            role = Role.Button
                            onClick(label = showAll) {
                                onOpenReactors()
                                true
                            }
                        },
                ) {
                    Text(text = post.topReactions.joinToString("") { it.emoji }, fontSize = 18.sp)
                    Text(
                        text = post.reactionTotal.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The five reactions in a row. HAHA is left out when the post's owner switched it off. */
@Composable
fun ReactionPicker(
    selected: ReactionType?,
    allowHaha: Boolean,
    onPick: (ReactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
        modifier = modifier
            .padding(bottom = BroTheme.spacing.sm)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge)
            .padding(BroTheme.spacing.xs),
    ) {
        ReactionType.entries
            .filter { allowHaha || it != ReactionType.HAHA }
            .forEach { reaction ->
                val isSelected = reaction == selected
                val description = stringResource(R.string.reaction_pick, stringResource(reaction.labelRes))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = BroTheme.spacing.touchTarget, minHeight = BroTheme.spacing.touchTarget)
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable(role = Role.Button) { onPick(reaction) }
                        .clearAndSetSemantics {
                            contentDescription = description
                            this.selected = isSelected
                            role = Role.Button
                            onClick {
                                onPick(reaction)
                                true
                            }
                        },
                ) {
                    BouncingEmoji(
                        reaction = reaction,
                        bounce = isSelected && reaction == ReactionType.HAHA,
                        large = true,
                    )
                }
            }
    }
}

private const val BOUNCE_PEAK = 1.45f

/** An emoji that does one playful bounce when [bounce] turns on (skipped with reduced motion). */
@Composable
private fun BouncingEmoji(reaction: ReactionType, bounce: Boolean, large: Boolean = false) {
    val reducedMotion = rememberReducedMotion()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(bounce, reducedMotion) {
        if (bounce && !reducedMotion) {
            scale.snapTo(BOUNCE_PEAK)
            scale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
            )
        }
    }
    Text(
        text = reaction.emoji,
        fontSize = if (large) 26.sp else 18.sp,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

/** Small rank chip for tight spaces (the "Your bros" strip). */
@Composable
fun RankDot(rank: Rank, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(rank.style.color, CircleShape),
    )
}
