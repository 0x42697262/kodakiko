// Adapted from https://github.com/mihonapp/mihon
// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0
package dev.kodakiko.ui.core.util

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import dev.kodakiko.ui.core.components.material.SECONDARY_ALPHA

fun Modifier.secondaryItemAlpha(): Modifier = this.alpha(SECONDARY_ALPHA)

fun Modifier.runOnEnterKeyPressed(action: () -> Unit): Modifier = this.onPreviewKeyEvent {
    when (it.key) {
        Key.Enter, Key.NumPadEnter -> {
            if (it.type == KeyEventType.KeyDown) {
                action()
                true
            } else {
                false
            }
        }

        else -> false
    }
}

@Composable
fun Modifier.showSoftKeyboard(show: Boolean): Modifier {
    if (!show) return this
    val focusRequester = remember { FocusRequester() }
    var openKeyboard by rememberSaveable { mutableStateOf(show) }
    LaunchedEffect(focusRequester) {
        if (openKeyboard) {
            focusRequester.requestFocus()
            openKeyboard = false
        }
    }
    return this.focusRequester(focusRequester)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.clearFocusOnSoftKeyboardHide(
    onFocusCleared: (() -> Unit)? = null,
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    var keyboardShowedSinceFocused by remember { mutableStateOf(false) }
    if (isFocused) {
        val imeVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        LaunchedEffect(imeVisible) {
            if (imeVisible) {
                keyboardShowedSinceFocused = true
            } else if (keyboardShowedSinceFocused) {
                focusManager.clearFocus()
                onFocusCleared?.invoke()
            }
        }
    }

    return this.onFocusChanged {
        if (isFocused != it.isFocused) {
            if (isFocused) {
                keyboardShowedSinceFocused = false
            }
            isFocused = it.isFocused
        }
    }
}
