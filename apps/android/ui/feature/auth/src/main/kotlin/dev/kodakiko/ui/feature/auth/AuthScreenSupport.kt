package dev.kodakiko.ui.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import dev.kodakiko.account.PasswordRule
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.components.FormHint
import dev.kodakiko.ui.core.components.material.padding
import dev.kodakiko.ui.core.format.passwordRuleTag
import dev.kodakiko.ui.core.format.passwordRuleText
import dev.kodakiko.ui.core.navigation.LocalRouteNavigator
import kotlinx.coroutines.flow.Flow

@Composable
internal fun AuthEvents(events: Flow<AuthEvent>) {
    val navigator = LocalRouteNavigator.current
    LaunchedEffect(events, navigator) {
        events.collect { event ->
            when (event) {
                is AuthEvent.Push -> navigator.push(event.route)
                is AuthEvent.ReplaceAll -> navigator.replaceAll(event.route)
            }
        }
    }
}

@Composable
internal fun PasswordHints(hints: List<PasswordRule>) {
    if (hints.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        hints.forEach { rule ->
            FormHint(text = stringResource(passwordRuleText(rule)), testTag = passwordRuleTag(rule))
        }
    }
}

@Composable
internal fun MismatchHint(mismatch: Boolean, text: String) {
    if (mismatch) FormHint(text = text, testTag = TestTags.Password.MISMATCH)
}
