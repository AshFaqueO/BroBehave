package com.brobehave.app.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.brobehave.app.AppViewModel
import com.brobehave.app.R
import com.brobehave.app.RootState
import com.brobehave.core.designsystem.component.ConfirmDialog
import com.brobehave.core.designsystem.component.EmptyState
import com.brobehave.core.designsystem.icon.BroIcons
import com.brobehave.core.ui.LoadingScreen
import com.brobehave.feature.auth.AuthScreen
import com.brobehave.feature.feed.FeedScreen
import com.brobehave.feature.onboarding.OnboardingScreen
import com.brobehave.feature.onboarding.SetupScreen
import com.brobehave.feature.profile.EditProfileScreen
import com.brobehave.feature.profile.FriendsScreen
import com.brobehave.feature.profile.ProfileScreen
import com.brobehave.feature.profile.SettingsScreen
import com.brobehave.feature.profile.UserProfileScreen
import kotlinx.serialization.Serializable

// Destinations (Navigation 3 type-safe keys). The first entry of the back stack is always
// one of the root keys, chosen from session + profile state; sub-screens are pushed on top
// of MainKey.
@Serializable
data object LoadingKey : NavKey

@Serializable
data object IntroKey : NavKey

@Serializable
data object AuthKey : NavKey

@Serializable
data object SetupKey : NavKey

@Serializable
data object UnreachableKey : NavKey

@Serializable
data object MainKey : NavKey

@Serializable
data class FriendsKey(val query: String? = null) : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object EditProfileKey : NavKey

@Serializable
data class UserProfileKey(val userId: String) : NavKey

private fun RootState.key(): NavKey = when (this) {
    RootState.LOADING -> LoadingKey
    RootState.INTRO -> IntroKey
    RootState.AUTH -> AuthKey
    RootState.SETUP -> SetupKey
    RootState.MAIN -> MainKey
    RootState.UNREACHABLE -> UnreachableKey
}

/** Replaces the whole stack with [root] (signing in/out must not leave old screens behind). */
private fun NavBackStack<NavKey>.resetTo(root: NavKey) {
    add(0, root)
    while (size > 1) removeAt(lastIndex)
}

/**
 * The app's navigation. Intro → Auth → Setup → Main is driven by state, not by callbacks:
 * when the session or profile changes, the root of the back stack changes with it.
 *
 * @param inviteUsername username from an invite link the app was opened with, if any.
 */
@Composable
fun BroBehaveNavShell(
    modifier: Modifier = Modifier,
    inviteUsername: String? = null,
    onInviteHandled: () -> Unit = {},
    viewModel: AppViewModel = hiltViewModel(),
) {
    val rootState by viewModel.state.collectAsStateWithLifecycle()
    val askForNotifications by viewModel.shouldAskForNotifications.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(LoadingKey)
    val rootKey = rootState.key()

    LaunchedEffect(rootKey) {
        if (backStack.firstOrNull() != rootKey) backStack.resetTo(rootKey)
    }
    LaunchedEffect(inviteUsername, rootKey) {
        if (inviteUsername != null && rootKey == MainKey) {
            backStack.add(FriendsKey(query = inviteUsername))
            onInviteHandled()
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            // Each destination gets its own saved state and its own ViewModels, cleared when it is popped.
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LoadingKey> { LoadingScreen() }
                entry<IntroKey> { OnboardingScreen(onContinue = viewModel::completeIntro) }
                entry<AuthKey> { AuthScreen() }
                entry<SetupKey> { SetupScreen() }
                entry<UnreachableKey> { UnreachableScreen(onRetry = viewModel::retry, onSignOut = viewModel::signOut) }
                entry<MainKey> {
                    MainScreen(
                        onOpenProfile = { backStack.add(UserProfileKey(it)) },
                        onOpenFriends = { backStack.add(FriendsKey()) },
                        onOpenSettings = { backStack.add(SettingsKey) },
                        onEditProfile = { backStack.add(EditProfileKey) },
                    )
                }
                entry<FriendsKey> { key ->
                    FriendsScreen(
                        onBack = { backStack.remove(key) },
                        onOpenProfile = { backStack.add(UserProfileKey(it)) },
                        initialQuery = key.query,
                    )
                }
                entry<SettingsKey> { key -> SettingsScreen(onBack = { backStack.remove(key) }) }
                entry<EditProfileKey> { key -> EditProfileScreen(onBack = { backStack.remove(key) }) }
                entry<UserProfileKey> { key ->
                    UserProfileScreen(userId = key.userId, onBack = { backStack.remove(key) })
                }
            },
        )
    }

    if (askForNotifications) {
        NotificationPermissionPrompt(onHandled = viewModel::onNotificationPromptHandled)
    }
}

/** The app's only two main pages. */
enum class MainTab(val labelRes: Int, val icon: ImageVector) {
    FEED(R.string.tab_feed, BroIcons.Feed),
    PROFILE(R.string.tab_profile, BroIcons.Profile),
}

@Composable
private fun MainScreen(
    onOpenProfile: (userId: String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
) {
    MainScaffold(
        feed = { modifier -> FeedScreen(onOpenProfile = onOpenProfile, onInvite = onOpenFriends, modifier = modifier) },
        profile = { modifier ->
            ProfileScreen(
                onOpenFriends = onOpenFriends,
                onOpenSettings = onOpenSettings,
                onEditProfile = onEditProfile,
                modifier = modifier,
            )
        },
    )
}

/** Bottom navigation with exactly two tabs. Content is passed in so it can be tested alone. */
@Composable
fun MainScaffold(
    feed: @Composable (Modifier) -> Unit,
    profile: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.FEED) }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            MainTab.FEED -> feed(Modifier.padding(innerPadding))
            MainTab.PROFILE -> profile(Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun UnreachableScreen(onRetry: () -> Unit, onSignOut: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        EmptyState(
            title = stringResource(R.string.unreachable_title),
            message = stringResource(R.string.unreachable_message),
            actionLabel = stringResource(R.string.unreachable_retry),
            onAction = onRetry,
        )
        TextButton(onClick = onSignOut) { Text(stringResource(R.string.unreachable_sign_out)) }
    }
}

/**
 * Android 13+: explain first, then show the system permission dialog. Asked once; the
 * user can change it later in system settings.
 */
@Composable
private fun NotificationPermissionPrompt(onHandled: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Older versions grant notifications at install time.
        LaunchedEffect(Unit) { onHandled() }
        return
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onHandled() }
    ConfirmDialog(
        title = stringResource(R.string.notify_title),
        message = stringResource(R.string.notify_message),
        confirmLabel = stringResource(R.string.notify_allow),
        dismissLabel = stringResource(R.string.notify_not_now),
        onConfirm = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onDismiss = onHandled,
    )
}
