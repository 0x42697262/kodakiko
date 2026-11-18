// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.components

enum class TriState {
    DISABLED,
    ENABLED_IS,
    ENABLED_NOT,
    ;

    fun next(): TriState = when (this) {
        DISABLED -> ENABLED_IS
        ENABLED_IS -> ENABLED_NOT
        ENABLED_NOT -> DISABLED
    }
}
