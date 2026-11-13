package com.brobehave.detection

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.designsystem.component.PrimaryButton
import com.brobehave.core.designsystem.component.SecondaryButton
import com.brobehave.core.designsystem.theme.BroTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class DetectionDisclosureViewModel @Inject constructor(
    private val controller: DetectionController,
    private val preferences: AppPreferences,
) : ViewModel() {
    /** The disclosure has been shown: do not open it automatically again. */
    fun onShown() {
        viewModelScope.launch { preferences.setPendingDetectionSetup(false) }
    }

    /** Call only after the consent checkbox was ticked AND Android's VPN dialog was accepted. */
    fun startDetection() = controller.start()
}

/**
 * The prominent disclosure for automatic detection. It is a full screen of its own, shown
 * BEFORE Android's VPN permission dialog, and nothing is turned on unless the user ticks
 * the (initially unticked) checkbox and taps "Turn on". "Not now" leaves the app fully usable.
 */
@Composable
fun DetectionDisclosureScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetectionDisclosureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.onShown() }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startDetection()
            onFinished()
        } else {
            denied = true
        }
    }

    DetectionDisclosureContent(
        denied = denied,
        onTurnOn = {
            // prepare() returns null when the user has already approved this app as a VPN.
            val systemDialog = VpnService.prepare(context)
            if (systemDialog == null) {
                viewModel.startDetection()
                onFinished()
            } else {
                vpnPermission.launch(systemDialog)
            }
        },
        onNotNow = onFinished,
        modifier = modifier,
    )
}

@Composable
internal fun DetectionDisclosureContent(
    denied: Boolean,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var consent by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.md),
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(BroTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.disclosure_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.disclosure_intro), style = MaterialTheme.typography.bodyLarge)

            Section(R.string.disclosure_checked_title, R.string.disclosure_checked_body)
            Section(R.string.disclosure_shared_title, R.string.disclosure_shared_body)
            Section(R.string.disclosure_never_title, R.string.disclosure_never_body)
            Section(R.string.disclosure_limits_title, R.string.disclosure_limits_body)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BroTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BroTheme.spacing.touchTarget)
                    .toggleable(value = consent, role = Role.Checkbox) { consent = it },
            ) {
                Checkbox(checked = consent, onCheckedChange = null)
                Text(text = stringResource(R.string.disclosure_consent), style = MaterialTheme.typography.bodyLarge)
            }
            if (denied) {
                Text(
                    text = stringResource(R.string.disclosure_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            PrimaryButton(
                text = stringResource(R.string.disclosure_turn_on),
                onClick = onTurnOn,
                enabled = consent,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = stringResource(R.string.disclosure_not_now),
                onClick = onNotNow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Section(titleRes: Int, bodyRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(BroTheme.spacing.xs)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
