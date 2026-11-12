package com.brobehave.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brobehave.core.designsystem.R
import com.brobehave.core.designsystem.theme.BroTheme
import kotlin.math.absoluteValue

private val AvatarColors = listOf(
    Color(0xFF7FB2FF),
    Color(0xFFB39DFF),
    Color(0xFFFFC857),
    Color(0xFF8FD3C7),
    Color(0xFFFF9248),
    Color(0xFFFF8FB1),
)
private val AvatarInk = Color(0xFF16181D)
private const val AVATAR_TEXT_RATIO = 0.42f

/**
 * Round avatar. PLACEHOLDER: shows the person's initial on a colour derived from their
 * name until avatar images are wired up.
 */
@Composable
fun Avatar(name: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val label = name?.trim().orEmpty()
    val color = AvatarColors[label.hashCode().absoluteValue % AvatarColors.size]
    val description = stringResource(R.string.avatar_description, label.ifEmpty { "?" })
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Text(
            text = label.take(1).uppercase().ifEmpty { "?" },
            color = AvatarInk,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * AVATAR_TEXT_RATIO).sp,
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.heightIn(min = BroTheme.spacing.touchTarget),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(top = BroTheme.spacing.md, bottom = BroTheme.spacing.xs)
            .semantics { heading() },
    )
}

/** A labelled switch; the whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BroTheme.spacing.touchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = BroTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A tappable settings line. */
@Composable
fun SettingsRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    destructive: Boolean = false,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BroTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = BroTheme.spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Avatar + name + @username, with optional trailing actions. */
@Composable
fun UserRow(
    displayName: String?,
    username: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BroTheme.spacing.touchTarget)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = BroTheme.spacing.sm),
    ) {
        Avatar(name = displayName ?: username)
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName ?: username.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (username != null) {
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}
