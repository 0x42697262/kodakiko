package dev.kodakiko.ui.feature.viewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kodakiko.ui.core.markdown.MarkdownView as CoreMarkdownView

@Composable
fun MarkdownView(source: String, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    CoreMarkdownView(source = source, onOpenLink = onOpenLink, modifier = modifier)
}
