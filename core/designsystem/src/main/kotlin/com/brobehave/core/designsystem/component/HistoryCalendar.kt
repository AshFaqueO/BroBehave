package com.brobehave.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

internal val SHEET_LIST_MAX_HEIGHT: Dp = 360.dp
private const val DAYS_IN_WEEK = 7
private const val DAY_FILL_ALPHA = 0.28f

@Composable
internal fun LoadingRow(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(BroTheme.spacing.lg),
    ) {
        CircularProgressIndicator()
    }
}

enum class DayStatus { NONE, CLEAN, FLAGGED, RELAPSE }

/**
 * Month view of the user's history. Each state has a colour AND a mark
 * (✓ clean, • slip, × relapse), so it never relies on colour alone.
 *
 * @param statusOf status of a date; called for every visible day.
 */
@Composable
fun HistoryCalendar(today: LocalDate, statusOf: (LocalDate) -> DayStatus, modifier: Modifier = Modifier) {
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    val month = YearMonth.from(today).plusMonths(monthOffset.toLong())
    val locale = Locale.getDefault()
    val dayFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)

    Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm), modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { monthOffset-- }) { Text("‹ " + stringResource(R.string.calendar_previous_month)) }
            Text(
                text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + month.year,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(onClick = { monthOffset++ }, enabled = monthOffset < 0) {
                Text(stringResource(R.string.calendar_next_month) + " ›")
            }
        }

        val leadingBlanks = month.atDay(1).dayOfWeek.value % DAYS_IN_WEEK
        val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(DAYS_IN_WEEK).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs), modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    DayCell(
                        date = date,
                        status = if (date == null || date.isAfter(today)) DayStatus.NONE else statusOf(date),
                        isToday = date == today,
                        description = date?.format(dayFormatter).orEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(DAYS_IN_WEEK - week.size) { Box(Modifier.weight(1f)) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md)) {
            LegendItem("✓", stringResource(R.string.calendar_clean), BroTheme.colors.dayClean)
            LegendItem("•", stringResource(R.string.calendar_flagged), BroTheme.colors.dayFlagged)
            LegendItem("×", stringResource(R.string.calendar_relapse), BroTheme.colors.dayRelapse)
        }
    }
}

@Composable
private fun DayCell(date: LocalDate?, status: DayStatus, isToday: Boolean, description: String, modifier: Modifier) {
    val (color, mark, label) = when (status) {
        DayStatus.CLEAN -> Triple(
            BroTheme.colors.dayClean,
            "✓",
            stringResource(R.string.calendar_day_clean, description),
        )
        DayStatus.FLAGGED -> Triple(
            BroTheme.colors.dayFlagged,
            "•",
            stringResource(R.string.calendar_day_flagged, description),
        )
        DayStatus.RELAPSE -> Triple(
            BroTheme.colors.dayRelapse,
            "×",
            stringResource(R.string.calendar_day_relapse, description),
        )
        DayStatus.NONE -> Triple(Color.Transparent, "", description)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .background(
                color = if (status == DayStatus.NONE) Color.Transparent else color.copy(alpha = DAY_FILL_ALPHA),
                shape = MaterialTheme.shapes.small,
            )
            .clearAndSetSemantics { if (date != null) contentDescription = label },
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Black else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(text = mark, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
private fun LegendItem(mark: String, label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = DAY_FILL_ALPHA), CircleShape),
        ) {
            Text(text = mark, style = MaterialTheme.typography.labelSmall, color = color)
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
