// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp

const val DISABLED_ALPHA = .38f
const val SECONDARY_ALPHA = .78f

class Padding {
    val extraLarge = 32.dp

    val large = 24.dp

    val medium = 16.dp

    val small = 8.dp

    val extraSmall = 4.dp
}

val MaterialTheme.padding: Padding
    get() = Padding()

internal object MaterialConstants {
    val FAB_SPACING = 16.dp
}
