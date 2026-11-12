package com.brobehave.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroBehaveTheme

private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private const val RING_ANIMATION_MS = 700

/** True when the user has turned system animations off (reduced motion). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * The hero of the Profile page: a progress ring toward the next rank with the streak
 * day count in the middle.
 *
 * @param progress progress toward the next rank, 0..1.
 */
@Composable
fun StreakRing(
    streakDays: Int,
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    strokeWidth: Dp = 18.dp,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val reducedMotion = rememberReducedMotion()
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (reducedMotion) snap() else tween(RING_ANIMATION_MS),
        label = "streakRingProgress",
    )
    val description = pluralStringResource(R.plurals.streak_ring_description, streakDays, streakDays)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(trackColor, START_ANGLE, FULL_CIRCLE, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(
                color = ringColor,
                startAngle = START_ANGLE,
                sweepAngle = FULL_CIRCLE * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = streakDays.toString(), style = MaterialTheme.typography.displayLarge)
            Text(
                // Only the unit word: the number above is the hero.
                text = pluralStringResource(R.plurals.streak_days, streakDays, streakDays)
                    .substringAfter(' '),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun StreakRingPreview() {
    BroBehaveTheme(darkTheme = true) { StreakRing(streakDays = 26, progress = 0.73f) }
}
