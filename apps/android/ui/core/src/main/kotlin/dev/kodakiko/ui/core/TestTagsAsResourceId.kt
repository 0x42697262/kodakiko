package dev.kodakiko.ui.core

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

fun Modifier.testTagsAsResourceIdRoot(): Modifier = semantics { testTagsAsResourceId = true }
