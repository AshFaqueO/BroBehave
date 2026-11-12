package com.brobehave.core.designsystem.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.brobehave.core.designsystem.R
import com.brobehave.core.model.Rank

/**
 * Visual identity of a rank. PLACEHOLDER: the design handoff supplies an original
 * illustrated avatar and a color per rank; until then each rank gets a color and its
 * name, so rank is never communicated by color alone.
 */
data class RankStyle(@StringRes val nameRes: Int, val color: Color, val onColor: Color)

val Rank.style: RankStyle
    get() = when (this) {
        Rank.CLOWN -> RankStyle(R.string.rank_clown, Color(0xFFFF8FB1), Color(0xFF3B0A1A))
        Rank.NOOB -> RankStyle(R.string.rank_noob, Color(0xFFB8BCC8), Color(0xFF1B1D24))
        Rank.NOVICE -> RankStyle(R.string.rank_novice, Color(0xFFE0A572), Color(0xFF2E1800))
        Rank.AVERAGE -> RankStyle(R.string.rank_average, Color(0xFF8FD3C7), Color(0xFF00201B))
        Rank.ADVANCED -> RankStyle(R.string.rank_advanced, Color(0xFF7FB2FF), Color(0xFF001B3F))
        Rank.SIGMA -> RankStyle(R.string.rank_sigma, Color(0xFFB39DFF), Color(0xFF1B0A52))
        Rank.CHAD -> RankStyle(R.string.rank_chad, Color(0xFFFFC857), Color(0xFF2B1D00))
        Rank.ABSOLUTE_CHAD -> RankStyle(R.string.rank_absolute_chad, Color(0xFFFF9248), Color(0xFF331200))
        Rank.GIGA_CHAD -> RankStyle(R.string.rank_giga_chad, Color(0xFFB6FF3C), Color(0xFF142000))
    }
