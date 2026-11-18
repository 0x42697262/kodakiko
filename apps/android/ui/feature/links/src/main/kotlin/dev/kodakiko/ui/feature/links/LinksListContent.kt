@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.kodakiko.service.links.OwnerLink
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.components.material.padding

@Composable
fun LinksListContent(state: LinksListState, onAction: (LinksListAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Links.LIST_SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.links_list_title),
                navigateUp = { onAction(LinksListAction.Back) },
                navigateUpTestTag = TestTags.Shared.BACK,
                scrollBehavior = it,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(LinksDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(LinksDimens.SECTION_SPACING),
        ) {
            if (state.error) {
                item { Banner(text = stringResource(R.string.errors_sharing_failed), kind = BannerKind.ERROR, testTag = TestTags.Links.ERROR) }
            }
            if (state.links != null && state.links.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.links_list_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TestTags.Links.LIST_EMPTY),
                    )
                }
            }
            itemsIndexed(state.links.orEmpty(), key = { _, link -> link.linkId }) { position, link ->
                LinkRow(link, position, state.nowMs, onAction)
            }
        }
    }
    state.revoking?.let { RevokeSheet(onAction) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkRow(link: OwnerLink, position: Int, nowMs: Long, onAction: (LinksListAction) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(listItem(TestTags.Links.ROW, position))) {
        Column(modifier = Modifier.padding(LinksDimens.SCREEN_PADDING), verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            Text(text = link.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = linkRowDetail(LocalContext.current.resources, link, nowMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                OutlinedButton(onClick = { onAction(LinksListAction.Copy(link)) }, modifier = Modifier.testTag(listItem(TestTags.Links.COPY_ROW, position))) {
                    Text(text = stringResource(R.string.links_copy_link))
                }
                OutlinedButton(onClick = { onAction(LinksListAction.Share(link)) }, modifier = Modifier.testTag(listItem(TestTags.Links.SHARE_ROW, position))) {
                    Text(text = stringResource(R.string.links_share))
                }
                OutlinedButton(
                    onClick = { onAction(LinksListAction.Revoke(link)) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag(listItem(TestTags.Links.REVOKE_ROW, position)),
                ) {
                    Text(text = stringResource(R.string.links_revoke))
                }
            }
        }
    }
}

@Composable
private fun RevokeSheet(onAction: (LinksListAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(LinksListAction.DismissRevoke) }, modifier = Modifier.testTag(TestTags.Links.REVOKE_SHEET)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LinksDimens.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(text = stringResource(R.string.links_revoke_confirm), style = MaterialTheme.typography.bodyLarge)
            DangerButton(text = stringResource(R.string.links_revoke_action), testTag = TestTags.Links.REVOKE_CONFIRM) {
                onAction(LinksListAction.ConfirmRevoke)
            }
        }
    }
}

@Composable
private fun DangerButton(text: String, testTag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Text(text = text)
    }
}
