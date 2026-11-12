package com.brobehave.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private const val VIEWPORT = 24f

/**
 * App icons as vectors, so the app doesn't depend on the Material icons artifact.
 * PLACEHOLDER shapes — the design handoff defines the final icon style.
 */
object BroIcons {
    /** Feed tab: three stacked cards. */
    val Feed: ImageVector by lazy {
        icon("Feed") {
            bar(top = 5f, bottom = 9f, right = 20f)
            bar(top = 11f, bottom = 14f, right = 20f)
            bar(top = 16f, bottom = 19f, right = 14f)
        }
    }

    /** Profile tab: head and shoulders. */
    val Profile: ImageVector by lazy {
        icon("Profile") {
            moveTo(x = 12f, y = 4f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = 8f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 0f, dy1 = -8f)
            close()
            moveTo(x = 4f, y = 20f)
            arcToRelative(8f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 16f, dy1 = 0f)
            close()
        }
    }

    private fun PathBuilder.bar(top: Float, bottom: Float, right: Float, left: Float = 4f) {
        moveTo(left, top)
        horizontalLineTo(right)
        verticalLineTo(bottom)
        horizontalLineTo(left)
        close()
    }

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, VIEWPORT.dp, VIEWPORT.dp, VIEWPORT, VIEWPORT)
            .apply { path(fill = SolidColor(Color.Black), pathBuilder = block) }
            .build()
}
