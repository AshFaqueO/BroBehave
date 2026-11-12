package com.brobehave.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.theme.BroTheme
import kotlinx.coroutines.launch

private data class IntroPage(val titleRes: Int, val bodyRes: Int)

private val IntroPages = listOf(
    IntroPage(R.string.intro_page1_title, R.string.intro_page1_body),
    IntroPage(R.string.intro_page2_title, R.string.intro_page2_body),
    IntroPage(R.string.intro_page3_title, R.string.intro_page3_body),
    IntroPage(R.string.intro_page4_title, R.string.intro_page4_body),
)

/**
 * First-run intro: four slides, then the 18+ confirmation. [onContinue] is only reachable
 * after the user ticks the (initially unticked) age checkbox.
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState { IntroPages.size }
    val scope = rememberCoroutineScope()
    var ageConfirmed by rememberSaveable { mutableStateOf(false) }
    val onLastPage = pagerState.currentPage == IntroPages.lastIndex

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(BroTheme.spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.intro_tagline),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
            val page = IntroPages[index]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.md, Alignment.CenterVertically),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BroTheme.spacing.sm),
            ) {
                Text(
                    text = stringResource(page.titleRes),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(page.bodyRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        val indicator = stringResource(R.string.intro_page_indicator, pagerState.currentPage + 1, IntroPages.size)
        Row(
            horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
            modifier = Modifier
                .padding(vertical = BroTheme.spacing.md)
                .clearAndSetSemantics { contentDescription = indicator },
        ) {
            repeat(IntroPages.size) { index ->
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            color = if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }

        if (onLastPage) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BroTheme.spacing.touchTarget)
                    .toggleable(value = ageConfirmed, role = Role.Checkbox) { ageConfirmed = it },
            ) {
                Checkbox(checked = ageConfirmed, onCheckedChange = null)
                Column {
                    Text(text = stringResource(R.string.intro_age_confirm), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.intro_age_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.intro_start),
                onClick = onContinue,
                enabled = ageConfirmed,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BroTheme.spacing.sm),
            )
        } else {
            PrimaryButton(
                text = stringResource(R.string.intro_next),
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
