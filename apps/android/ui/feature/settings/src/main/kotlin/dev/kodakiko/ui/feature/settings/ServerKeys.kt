@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.UserDto
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.settings.widget.PreferenceGroupHeader
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ServerKeysState(val facts: ServerFactsInput?)

sealed interface ServerKeysAction {
    data object Back : ServerKeysAction
}

class ServerKeysViewModel(private val deps: AccountSettingsDeps) : ViewModel() {
    private class Loaded(val serverUrl: String?, val account: AccountFacts, val fingerprint: String, val keyStorage: KeyStorage, val local: Boolean)

    private val loaded = MutableStateFlow<Loaded?>(null)
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<ServerKeysState> = combine(loaded, deps.settings.settings.settings, deps.profile.user, ::stateOf).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
        stateOf(loaded.value, deps.settings.settings.settings.value, deps.profile.user.value),
    )

    init {
        viewModelScope.launch { loaded.value = load() }
    }

    fun onAction(action: ServerKeysAction) {
        when (action) {
            ServerKeysAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private suspend fun load(): Loaded? {
        val profile = deps.profile
        val account = attempt { profile.accountFacts() } ?: return null
        val local = deps.settings.account.mode.value.isLocal()
        val serverUrl = if (local) null else attempt { profile.serverUrl() }
        val keyStorage = attempt { profile.keyStorage() } ?: KeyStorage(persistent = false, biometric = false)
        val fingerprint = if (local) "" else attempt { profile.securityCode() }.orEmpty()
        return Loaded(serverUrl, account, fingerprint, keyStorage, local)
    }

    private fun stateOf(current: Loaded?, settings: DeviceSettings, user: UserDto?): ServerKeysState = ServerKeysState(
        current?.let {
            ServerFactsInput(
                serverUrl = it.serverUrl,
                config = deps.settings.config.current(),
                user = user?.let { known -> ServerUsage(usedBytes = known.usedBytes, quotaBytes = known.quotaBytes) },
                account = it.account,
                fingerprint = it.fingerprint,
                keyStorage = it.keyStorage,
                encryptThumbnails = settings.encryptThumbnails,
                local = it.local,
            )
        },
    )
}

@Composable
fun ServerKeysScreen(viewModel: ServerKeysViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    ServerKeysContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun ServerKeysContent(state: ServerKeysState, onAction: (ServerKeysAction) -> Unit) {
    val resources = LocalResources.current
    val sections = remember(resources, state.facts) { state.facts?.let { serverFactSections(resources, it) }.orEmpty() }
    Scaffold(
        modifier = Modifier.testTag(TestTags.Server.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.server_title),
                navigateUp = { onAction(ServerKeysAction.Back) },
                navigateUpTestTag = TestTags.Settings.BACK,
                scrollBehavior = it,
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            sections.forEach { section ->
                PreferenceGroupHeader(title = section.title)
                Column(verticalArrangement = Arrangement.spacedBy(SettingsDimens.ROW_SPACING)) {
                    section.rows.forEach { row -> FactRow(row) }
                }
            }
        }
    }
}

@Composable
private fun FactRow(row: ServerFactRow) {
    LabeledValue(label = row.label, value = row.value)
}
