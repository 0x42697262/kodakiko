package dev.kodakiko.ui.feature.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind

@Composable
fun EntryNewContent(state: EntryNewState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(EditDimens.SCREEN_PADDING)
            .testTag(TestTags.Edit.CREATING),
        contentAlignment = Alignment.Center,
    ) {
        if (state.failed) {
            Banner(text = stringResource(R.string.errors_generic), kind = BannerKind.ERROR, testTag = TestTags.Compose.FAILURE)
        } else {
            val label = stringResource(R.string.edit_creating)
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = label }.testTag(TestTags.Edit.CREATING_SPINNER))
        }
    }
}
