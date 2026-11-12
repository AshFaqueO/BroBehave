package com.brobehave.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.brobehave.core.data.DataError
import com.brobehave.core.model.StreakMath
import java.time.Duration
import java.time.Instant

/** User-facing sentence for a [DataError]. */
@Composable
fun errorMessage(error: DataError): String = stringResource(
    when (error) {
        DataError.NETWORK -> R.string.error_network
        DataError.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
        DataError.EMAIL_IN_USE -> R.string.error_email_in_use
        DataError.EMAIL_NOT_CONFIRMED -> R.string.error_email_not_confirmed
        DataError.WEAK_PASSWORD -> R.string.error_weak_password
        DataError.RATE_LIMITED -> R.string.error_rate_limited
        DataError.USERNAME_TAKEN -> R.string.error_username_taken
        DataError.USERNAME_INVALID -> R.string.error_username_invalid
        DataError.NOT_ALLOWED -> R.string.error_not_allowed
        DataError.NOT_SIGNED_IN -> R.string.error_not_signed_in
        DataError.UNKNOWN -> R.string.error_unknown
    },
)

/** Inline error line, announced by screen readers when it appears. */
@Composable
fun ErrorText(error: DataError?, modifier: Modifier = Modifier) {
    if (error != null) {
        Text(
            text = errorMessage(error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        CircularProgressIndicator()
    }
}

/** Top bar for sub-screens: a title and a Back action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.action_back)) } },
        modifier = modifier,
    )
}

/** Short relative time for a post ("now", "5m", "3h", "2d"). */
@Composable
fun relativeTime(timestamp: String, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(runCatching { StreakMath.parseInstant(timestamp) }.getOrDefault(now), now)
    return when {
        elapsed.toMinutes() < 1 -> stringResource(R.string.time_now)
        elapsed.toHours() < 1 -> stringResource(R.string.time_minutes, elapsed.toMinutes().toInt())
        elapsed.toDays() < 1 -> stringResource(R.string.time_hours, elapsed.toHours().toInt())
        else -> stringResource(R.string.time_days, elapsed.toDays().toInt())
    }
}
