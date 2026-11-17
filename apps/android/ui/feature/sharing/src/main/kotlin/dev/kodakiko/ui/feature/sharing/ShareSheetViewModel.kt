package dev.kodakiko.ui.feature.sharing

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.COLLECTION_NAME_MAX_CHARS
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.AddEntriesResult
import dev.kodakiko.service.sharing.CollectionName
import dev.kodakiko.service.sharing.ShareSelection
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShareTab {
    IN_KODAKIKO,
    TO_APPS,
}

enum class InKodakikoPanel {
    MENU,
    JOURNALS,
    NEW,
}

@Immutable
data class JournalChoice(val id: String, val name: CollectionName)

@Immutable
sealed interface ShareEntriesOutcome {
    data class Added(val result: AddEntriesResult) : ShareEntriesOutcome

    data class Failed(val message: SharingMessage) : ShareEntriesOutcome
}

@Immutable
data class InKodakikoState(
    val panel: InKodakikoPanel,
    val sharingEnabled: Boolean,
    val linksEnabled: Boolean,
    val journals: List<JournalChoice>,
    val name: String,
    val busy: Boolean,
    val outcome: ShareEntriesOutcome?,
)

@Immutable
data class ToAppsState(
    val items: List<SharePickItem>?,
    val chosen: Set<String>,
    val option: ShareTextOption,
    val selection: ShareSelection,
    val busy: Boolean,
    val failed: Boolean,
)

@Immutable
data class ShareSheetState(
    val inKodakiko: Boolean,
    val tab: ShareTab,
    val kodakiko: InKodakikoState,
    val apps: ToAppsState,
)

sealed interface ShareSheetAction {
    data class SelectTab(val tab: ShareTab) : ShareSheetAction

    data object AddTo : ShareSheetAction

    data class AddToJournal(val collectionId: String) : ShareSheetAction

    data object NewJournal : ShareSheetAction

    data class NameChanged(val name: String) : ShareSheetAction

    data object CreateJournal : ShareSheetAction

    data object CreateLink : ShareSheetAction

    data class Toggle(val mediaId: String) : ShareSheetAction

    data class ChooseText(val option: ShareTextOption) : ShareSheetAction

    data object Send : ShareSheetAction

    data object Dismiss : ShareSheetAction
}

sealed interface ShareSheetEvent {
    data class Replace(val route: Route) : ShareSheetEvent

    data object Close : ShareSheetEvent
}

class ShareSheetViewModel(private val deps: SharingDeps, private val entryIds: List<String>, inKodakiko: Boolean) : ViewModel() {
    private val channel = Channel<ShareSheetEvent>(Channel.BUFFERED)
    private val mutableState: MutableStateFlow<ShareSheetState>
    private var itemsRequested = false

    val events: Flow<ShareSheetEvent> = channel.receiveAsFlow()

    val state: StateFlow<ShareSheetState>

    init {
        val config = deps.config.current()
        val server = deps.accountMode.value == AccountMode.SERVER
        val sharingEnabled = server && config?.sharingEnabled == true
        val linksEnabled = server && config?.publicLinksEnabled == true
        val kodakiko = inKodakiko && (sharingEnabled || linksEnabled)
        mutableState = MutableStateFlow(
            ShareSheetState(
                inKodakiko = kodakiko,
                tab = if (kodakiko) ShareTab.IN_KODAKIKO else ShareTab.TO_APPS,
                kodakiko = InKodakikoState(
                    panel = InKodakikoPanel.MENU,
                    sharingEnabled = sharingEnabled,
                    linksEnabled = linksEnabled,
                    journals = emptyList(),
                    name = "",
                    busy = false,
                    outcome = null,
                ),
                apps = ToAppsState(
                    items = null,
                    chosen = emptySet(),
                    option = ShareTextOption.NONE,
                    selection = deps.shareOut.selection(emptyList(), emptySet()),
                    busy = false,
                    failed = false,
                ),
            ),
        )
        state = mutableState.asStateFlow()
        if (!kodakiko) loadItems()
    }

    fun onAction(action: ShareSheetAction) {
        when (action) {
            is ShareSheetAction.SelectTab -> selectTab(action.tab)
            ShareSheetAction.AddTo -> addTo()
            is ShareSheetAction.AddToJournal -> addEntries(InKodakikoPanel.JOURNALS) { action.collectionId }
            ShareSheetAction.NewJournal -> updateKodakiko { it.copy(panel = InKodakikoPanel.NEW, name = "") }
            is ShareSheetAction.NameChanged -> updateKodakiko { it.copy(name = action.name.take(COLLECTION_NAME_MAX_CHARS)) }
            ShareSheetAction.CreateJournal -> createJournal()
            ShareSheetAction.CreateLink -> if (mutableState.value.kodakiko.linksEnabled) channel.trySend(ShareSheetEvent.Replace(Route.LinkNew(entryIds)))
            is ShareSheetAction.Toggle -> toggle(action.mediaId)
            is ShareSheetAction.ChooseText -> updateApps { it.copy(option = action.option) }
            ShareSheetAction.Send -> send()
            ShareSheetAction.Dismiss -> channel.trySend(ShareSheetEvent.Close)
        }
    }

    private fun updateKodakiko(change: (InKodakikoState) -> InKodakikoState) {
        mutableState.update { it.copy(kodakiko = change(it.kodakiko)) }
    }

    private fun updateApps(change: (ToAppsState) -> ToAppsState) {
        mutableState.update { it.copy(apps = change(it.apps)) }
    }

    private fun selectTab(tab: ShareTab) {
        if (!mutableState.value.inKodakiko) return
        mutableState.update { it.copy(tab = tab) }
        if (tab == ShareTab.TO_APPS) loadItems()
    }

    private fun loadItems() {
        if (itemsRequested) return
        itemsRequested = true
        viewModelScope.launch {
            attempt { deps.shareOut.items(entryIds) }
                .onSuccess { items ->
                    val chosen = items.map { it.media.mediaId }.toSet()
                    updateApps { it.copy(items = items, chosen = chosen, selection = deps.shareOut.selection(items, chosen)) }
                }
                .onFailure { updateApps { it.copy(failed = true) } }
        }
    }

    private fun toggle(mediaId: String) {
        updateApps { apps ->
            val chosen = if (mediaId in apps.chosen) apps.chosen - mediaId else apps.chosen + mediaId
            apps.copy(chosen = chosen, selection = deps.shareOut.selection(apps.items.orEmpty(), chosen))
        }
    }

    private fun send() {
        val apps = mutableState.value.apps
        val items = apps.items ?: return
        if (apps.busy || apps.chosen.isEmpty()) return
        updateApps { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val failed = attempt { deps.shareOut.share(items, apps.chosen, apps.option) }.isFailure
            updateApps { it.copy(busy = false, failed = failed) }
        }
    }

    private fun addTo() {
        if (!mutableState.value.kodakiko.sharingEnabled) return
        updateKodakiko { it.copy(panel = InKodakikoPanel.JOURNALS) }
        viewModelScope.launch {
            val journals = attempt { loadJournals(deps) }.getOrNull().orEmpty()
            val mine = journals.filter { it.role == CollectionRole.OWNER }.map { JournalChoice(it.id, it.name) }
            updateKodakiko { it.copy(journals = mine) }
        }
    }

    private fun createJournal() {
        val name = jsTrim(mutableState.value.kodakiko.name)
        if (name.isEmpty()) return
        addEntries(InKodakikoPanel.NEW) { deps.sharing.createJournal(name) }
    }

    private fun addEntries(panel: InKodakikoPanel, collectionId: suspend () -> String) {
        val current = mutableState.value.kodakiko
        if (current.busy || !current.sharingEnabled) return
        updateKodakiko { it.copy(busy = true, outcome = null) }
        viewModelScope.launch {
            attempt { deps.sharing.addEntries(collectionId(), entryIds) }
                .onSuccess { result -> updateKodakiko { it.copy(busy = false, outcome = ShareEntriesOutcome.Added(result), panel = InKodakikoPanel.MENU) } }
                .onFailure { failure -> updateKodakiko { it.copy(busy = false, outcome = ShareEntriesOutcome.Failed(sharingMessage(failure)), panel = panel) } }
        }
    }
}
