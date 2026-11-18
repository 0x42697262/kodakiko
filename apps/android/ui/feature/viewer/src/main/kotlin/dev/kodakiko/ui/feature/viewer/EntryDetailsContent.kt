@file:OptIn(ExperimentalMaterial3Api::class)

package dev.kodakiko.ui.feature.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.kodakiko.service.stats.DetailSection
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.AppBar
import dev.kodakiko.ui.core.components.SettingsItemsPaddings
import dev.kodakiko.ui.core.components.material.Scaffold
import dev.kodakiko.ui.core.format.detailLabelText
import dev.kodakiko.ui.core.format.detailSectionTitle
import dev.kodakiko.ui.core.format.detailValueText

@Composable
fun EntryDetailsContent(state: EntryDetailsState, onAction: (EntryDetailsAction) -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.testTag(TestTags.Details.SCREEN),
        topBar = {
            AppBar(
                title = stringResource(R.string.details_title),
                navigateUp = { onAction(EntryDetailsAction.Back) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = ViewerDimens.SMALL_SPACING),
            verticalArrangement = Arrangement.spacedBy(ViewerDimens.SCREEN_PADDING),
        ) {
            state.sections.orEmpty().forEach { DetailSectionView(it) }
        }
    }
}

@Composable
private fun DetailSectionView(section: DetailSection) {
    val resources = LocalResources.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ViewerDimens.SMALL_SPACING)) {
        Text(
            text = detailSectionTitle(resources, section),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        section.rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(ViewerDimens.TINY_SPACING)) {
                Text(
                    text = detailLabelText(resources, row.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(text = detailValueText(resources, row.value), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
