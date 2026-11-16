package dev.kodakiko.ui.core.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

fun Modifier.optionalTestTag(tag: String?): Modifier = if (tag == null) this else testTag(tag)
