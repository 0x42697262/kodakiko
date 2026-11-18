package dev.kodakiko.ui.feature.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SettingsRow(val testTag: String, val route: Route?, @param:StringRes val title: Int, @param:StringRes val summary: Int?) {
    APPEARANCE(TestTags.Settings.ROW_APPEARANCE, Route.SettingsAppearance, R.string.prefs_appearance, R.string.prefs_appearance_summary),
    GALLERY(TestTags.Settings.ROW_GALLERY, Route.SettingsGallery, R.string.prefs_gallery, R.string.prefs_gallery_summary),
    TAGS(TestTags.Settings.ROW_TAGS, Route.Tags, R.string.more_tags, R.string.tags_settings_summary),
    CAPTURE(TestTags.Settings.ROW_CAPTURE, Route.SettingsCapture, R.string.prefs_capture, R.string.prefs_capture_summary),
    SYNC(TestTags.Settings.ROW_SYNC, Route.SettingsSync, R.string.settings_row_sync, R.string.prefs_sync_summary),
    STORAGE(TestTags.Settings.ROW_STORAGE, Route.SettingsStorage, R.string.prefs_data_storage, R.string.prefs_data_storage_summary),
    SECURITY(TestTags.Settings.ROW_SECURITY, Route.SettingsSecurity, R.string.prefs_security, R.string.prefs_security_summary),
    ACCOUNT(TestTags.Settings.ROW_ACCOUNT, Route.SettingsAccount, R.string.settings_row_account, R.string.prefs_account_summary),
    LINKS(TestTags.Settings.ROW_LINKS, Route.SettingsLinks, R.string.settings_row_links, R.string.prefs_links_summary),
    ADMIN(TestTags.Settings.ROW_ADMIN, Route.SettingsAdmin, R.string.settings_row_admin, R.string.prefs_admin_summary),
    ADVANCED(TestTags.Settings.ROW_ADVANCED, Route.SettingsAdvanced, R.string.advanced_title, R.string.advanced_summary),
    ABOUT(TestTags.Settings.ROW_ABOUT, Route.SettingsAbout, R.string.settings_row_about, R.string.prefs_about_summary),
    LOCK_NOW(TestTags.Settings.ROW_LOCK_NOW, null, R.string.settings_lock_now, null),
    SIGN_OUT(TestTags.Settings.ROW_SIGN_OUT, null, R.string.settings_sign_out, null),
}

fun settingsRows(local: Boolean, publicLinksEnabled: Boolean, admin: Boolean): List<SettingsRow> = SettingsRow.entries.filter { row ->
    when (row) {
        SettingsRow.LINKS -> !local && publicLinksEnabled
        SettingsRow.ADMIN -> !local && admin
        SettingsRow.SIGN_OUT -> !local
        else -> true
    }
}

@Immutable
data class SettingsState(val rows: List<SettingsRow>, val confirmingSignOut: Boolean)

sealed interface SettingsAction {
    data class Open(val row: SettingsRow) : SettingsAction

    data object ConfirmSignOut : SettingsAction

    data object DismissSignOut : SettingsAction

    data object Search : SettingsAction

    data object Back : SettingsAction
}

class SettingsViewModel(private val deps: SettingsDeps) : ViewModel() {
    private val admin = MutableStateFlow(false)
    private val confirmingSignOut = MutableStateFlow(false)
    private val config = MutableStateFlow(deps.config.current())
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<SettingsState> = combine(deps.account.mode, config, admin, confirmingSignOut) { mode, current, isAdmin, confirming ->
        stateOf(mode, current, isAdmin, confirming)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SettingsConstants.STATE_SUBSCRIPTION_MS),
        stateOf(deps.account.mode.value, config.value, admin.value, confirmingSignOut.value),
    )

    init {
        val unsubscribe = deps.config.subscribe { config.value = it }
        addCloseable { unsubscribe() }
        viewModelScope.launch {
            merge(flowOf(Unit), deps.account.roleChanges).collect { admin.value = attempt { deps.account.isAdmin() } ?: false }
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.Open -> open(action.row)
            SettingsAction.ConfirmSignOut -> signOut()
            SettingsAction.DismissSignOut -> confirmingSignOut.value = false
            SettingsAction.Search -> channel.trySend(SettingsEvent.Open(Route.SettingsSearch))
            SettingsAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun open(row: SettingsRow) {
        when (row) {
            SettingsRow.LOCK_NOW -> viewModelScope.launch { attempt { deps.account.lockNow() } }
            SettingsRow.SIGN_OUT -> if (deps.sync.status.value.queued > 0) confirmingSignOut.value = true else signOut()
            else -> row.route?.let { channel.trySend(SettingsEvent.Open(it)) }
        }
    }

    private fun signOut() {
        confirmingSignOut.value = false
        viewModelScope.launch { attempt { deps.account.signOut() } }
    }

    private fun stateOf(mode: AccountMode, config: PublicConfigDto?, isAdmin: Boolean, confirming: Boolean): SettingsState = SettingsState(
        rows = settingsRows(local = mode == AccountMode.LOCAL, publicLinksEnabled = config?.publicLinksEnabled == true, admin = isAdmin),
        confirmingSignOut = confirming,
    )
}
