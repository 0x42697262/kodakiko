package dev.kodakiko.app.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormButtonStyle
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.screens.FormScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.kodakiko.app.TestTags as AppTestTags

@Immutable
data class PlaceholderState(val username: String? = null)

class PlaceholderViewModel(
    private val auth: AuthService,
    accountState: Observable<AccountState>,
) : ViewModel() {
    val state: StateFlow<PlaceholderState> = accountState.flow
        .map { PlaceholderState(username = (it as? AccountState.Unlocked)?.username) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(NavigationConstants.STATE_SUBSCRIPTION_MS),
            PlaceholderState((accountState.value as? AccountState.Unlocked)?.username),
        )

    fun onSignOut() {
        viewModelScope.launch {
            try {
                auth.signOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return@launch
            }
        }
    }
}

@Composable
fun PlaceholderScreen(viewModel: PlaceholderViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlaceholderContent(state = state, onSignOut = viewModel::onSignOut)
}

@Composable
fun PlaceholderContent(state: PlaceholderState, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    FormScreen(
        modifier = modifier.testTag(AppTestTags.APP_PLACEHOLDER),
        icon = KodakikoIcons.PhotoLibrary,
        subtitleText = stringResource(R.string.placeholder_coming_soon),
    ) {
        state.username?.let { username ->
            Text(
                text = stringResource(R.string.home_signed_in_as, username),
                modifier = Modifier.testTag(TestTags.Home.SIGNED_IN_AS),
                style = MaterialTheme.typography.titleMedium,
            )
            FormButton(
                text = stringResource(R.string.home_sign_out),
                onClick = onSignOut,
                testTag = TestTags.Home.SIGN_OUT,
                style = FormButtonStyle.SECONDARY,
            )
        }
    }
}
