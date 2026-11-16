// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.util.optionalTestTag

@Composable
fun LoadingScreen(
    modifier: Modifier = Modifier,
    indicatorTestTag: String? = null,
) {
    val label = stringResource(R.string.app_loading)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .semantics { contentDescription = label }
                .optionalTestTag(indicatorTestTag),
        )
    }
}
