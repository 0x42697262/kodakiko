@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.sharing

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.kodakiko.js.jsTrim
import dev.kodakiko.service.shareout.SharePickItem
import dev.kodakiko.service.sharing.ShareTextOption
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.material.TabText
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.format.collectionNameText
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.icons.KodakikoIcons

private val textOptions: List<Pair<ShareTextOption, String>> = listOf(
    ShareTextOption.NONE to TestTags.Share.TEXT_NONE,
    ShareTextOption.CAPTIONS to TestTags.Share.TEXT_CAPTIONS,
    ShareTextOption.CAPTIONS_AND_NOTE to TestTags.Share.TEXT_CAPTIONS_AND_NOTE,
)

@Composable
fun ShareSheetContent(state: ShareSheetState, onAction: (ShareSheetAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (state.inKodakiko) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Tab(
                    selected = state.tab == ShareTab.IN_KODAKIKO,
                    onClick = { onAction(ShareSheetAction.SelectTab(ShareTab.IN_KODAKIKO)) },
                    text = { TabText(text = stringResource(R.string.share_in_kodakiko)) },
                    modifier = Modifier.testTag(TestTags.Sheet.TAB_KODAKIKO),
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
                Tab(
                    selected = state.tab == ShareTab.TO_APPS,
                    onClick = { onAction(ShareSheetAction.SelectTab(ShareTab.TO_APPS)) },
                    text = { TabText(text = stringResource(R.string.share_to_apps)) },
                    modifier = Modifier.testTag(TestTags.Sheet.TAB_APPS),
                    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.share_to_apps),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = SharingDimens.SHEET_PADDING, end = SharingDimens.SHEET_PADDING, top = SharingDimens.SHEET_PADDING),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(SharingDimens.SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            if (state.inKodakiko && state.tab == ShareTab.IN_KODAKIKO) InKodakiko(state.kodakiko, onAction) else ToApps(state.apps, onAction)
        }
    }
}

@Composable
private fun InKodakiko(state: InKodakikoState, onAction: (ShareSheetAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.ShareEntries.SHEET), verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.outcome?.let { Outcome(it) }
        when (state.panel) {
            InKodakikoPanel.MENU -> KodakikoMenu(state, onAction)
            InKodakikoPanel.JOURNALS -> {
                state.journals.forEachIndexed { position, journal ->
                    ActionRow(
                        icon = null,
                        label = collectionNameText(LocalContext.current.resources, journal.name),
                        testTag = listItem(TestTags.ShareEntries.JOURNAL, position),
                        enabled = !state.busy,
                    ) { onAction(ShareSheetAction.AddToJournal(journal.id)) }
                }
                ActionRow(null, stringResource(R.string.shared_new), TestTags.ShareEntries.NEW_JOURNAL, enabled = !state.busy) {
                    onAction(ShareSheetAction.NewJournal)
                }
            }
            InKodakikoPanel.NEW -> {
                NameField(name = state.name, onChange = { onAction(ShareSheetAction.NameChanged(it)) }, onDone = { onAction(ShareSheetAction.CreateJournal) })
                FormButton(
                    text = stringResource(R.string.shared_create),
                    onClick = { onAction(ShareSheetAction.CreateJournal) },
                    testTag = TestTags.Shared.CREATE,
                    enabled = jsTrim(state.name).isNotEmpty(),
                    busy = state.busy,
                )
            }
        }
    }
}

@Composable
private fun Outcome(outcome: ShareEntriesOutcome) {
    val resources = LocalContext.current.resources
    when (outcome) {
        is ShareEntriesOutcome.Added -> Banner(text = addEntriesResultText(resources, outcome.result), kind = BannerKind.INFO, testTag = TestTags.ShareEntries.RESULT)
        is ShareEntriesOutcome.Failed -> Banner(text = sharingMessageText(resources, outcome.message), kind = BannerKind.ERROR, testTag = TestTags.ShareEntries.RESULT)
    }
}

@Composable
private fun KodakikoMenu(state: InKodakikoState, onAction: (ShareSheetAction) -> Unit) {
    ActionRow(KodakikoIcons.People, stringResource(R.string.shared_add_to), TestTags.ShareEntries.ADD_TO, enabled = state.sharingEnabled && !state.busy) {
        onAction(ShareSheetAction.AddTo)
    }
    if (!state.sharingEnabled) Hint(stringResource(R.string.shared_sharing_off_hint), TestTags.Shared.SHARING_OFF_HINT)
    ActionRow(SharingIcons.Link, stringResource(R.string.links_create_link), TestTags.ShareEntries.CREATE_LINK, enabled = state.linksEnabled) {
        onAction(ShareSheetAction.CreateLink)
    }
    if (!state.linksEnabled) Hint(stringResource(R.string.links_off_hint), TestTags.Links.OFF_HINT)
}

@Composable
private fun Hint(text: String, testTag: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(testTag))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToApps(state: ToAppsState, onAction: (ShareSheetAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(TestTags.Share.PICKER), verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        Banner(text = stringResource(R.string.share_leaves_encryption), kind = BannerKind.INFO, testTag = TestTags.Share.LEAVES_ENCRYPTION)
        val items = state.items
        if (items == null) {
            if (!state.failed) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(SharingDimens.PICK_SPACING), verticalArrangement = Arrangement.spacedBy(SharingDimens.PICK_SPACING)) {
                items.forEachIndexed { position, item ->
                    PickTile(item, position, item.media.mediaId in state.chosen) { onAction(ShareSheetAction.Toggle(item.media.mediaId)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                textOptions.forEach { (option, testTag) ->
                    FilterChip(
                        selected = state.option == option,
                        onClick = { onAction(ShareSheetAction.ChooseText(option)) },
                        label = { Text(text = textOptionLabel(option)) },
                        modifier = Modifier.testTag(testTag),
                    )
                }
            }
            if (state.selection.warn) {
                Banner(text = stringResource(R.string.share_too_big, formatBytes(state.selection.bytes)), kind = BannerKind.WARNING, testTag = TestTags.Share.TOO_BIG)
            }
        }
        if (state.failed) Banner(text = stringResource(R.string.share_failed), kind = BannerKind.ERROR, testTag = TestTags.Share.FAILED)
        if (items != null) {
            FormButton(
                text = pluralStringResource(R.plurals.share_send, state.selection.count, state.selection.count, formatBytes(state.selection.bytes)),
                onClick = { onAction(ShareSheetAction.Send) },
                testTag = TestTags.Share.SEND,
                enabled = state.selection.count > 0,
                busy = state.busy,
            )
        }
    }
}

@Composable
private fun textOptionLabel(option: ShareTextOption): String = when (option) {
    ShareTextOption.NONE -> stringResource(R.string.share_text_none)
    ShareTextOption.CAPTIONS -> stringResource(R.string.share_text_captions)
    ShareTextOption.CAPTIONS_AND_NOTE -> stringResource(R.string.share_text_captions_and_note)
}

@Composable
private fun PickTile(item: SharePickItem, position: Int, checked: Boolean, onToggle: () -> Unit) {
    val number = position + SharingConstants.FIRST_ITEM_NUMBER
    val label = if (checked) stringResource(R.string.share_item_label_selected, number) else stringResource(R.string.share_item_label, number)
    val shape = RoundedCornerShape(SharingDimens.TILE_RADIUS)
    Box(
        modifier = Modifier
            .size(SharingDimens.PICK_TILE)
            .clip(shape)
            .border(SharingDimens.TILE_BORDER, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .testTag(listItem(TestTags.Share.ITEM, position))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
    ) {
        Thumbnail(entryId = item.entryId, mediaId = item.media.mediaId, modifier = Modifier.fillMaxSize())
        Icon(
            imageVector = if (checked) KodakikoIcons.CheckBox else KodakikoIcons.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.TopEnd).padding(SharingDimens.CHECK_PADDING).size(SharingDimens.CHECK_SIZE),
        )
    }
}
