package dev.kodakiko.ui.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dev.kodakiko.service.account.AuthError
import dev.kodakiko.service.account.AuthFailure
import dev.kodakiko.service.account.AuthService
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.ServerChoice
import dev.kodakiko.service.account.ServerChoiceFailure
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.errorBanner
import dev.kodakiko.ui.core.navigation.Route
import kotlinx.coroutines.launch

@Immutable
data class WelcomeState(
    val server: String = "",
    val busy: Boolean = false,
    val error: BannerMessage? = null,
)

class WelcomeViewModel(
    private val auth: AuthService,
    private val inviteToken: Observable<String?>,
) : FormViewModel<WelcomeState>(WelcomeState()) {
    fun onServerChange(value: String) {
        update { it.copy(server = value) }
    }

    fun onContinue() {
        if (current.busy) return
        update { it.copy(busy = true, error = null) }
        val input = current.server
        viewModelScope.launch {
            attempt { auth.chooseServer(input) }
                .onSuccess { choice ->
                    when (choice) {
                        is ServerChoice.Ok -> {
                            inviteToken.value = choice.inviteToken
                            emit(AuthEvent.Push(if (choice.inviteToken == null) Route.SignIn else Route.SignUp))
                        }
                        is ServerChoice.Failed -> update { it.copy(error = errorBanner(AuthError(failureOf(choice.reason)))) }
                    }
                }
                .onFailure { failure -> update { it.copy(error = errorBanner(failure)) } }
            update { it.copy(busy = false) }
        }
    }

    fun onUseWithoutServer() {
        emit(AuthEvent.Push(Route.LocalSetup))
    }

    private fun failureOf(reason: ServerChoiceFailure): AuthFailure = when (reason) {
        ServerChoiceFailure.NOT_HTTPS -> AuthFailure.NOT_HTTPS
        ServerChoiceFailure.UNREACHABLE -> AuthFailure.UNREACHABLE
        ServerChoiceFailure.INVALID, ServerChoiceFailure.NOT_KODAKIKO -> AuthFailure.NOT_KODAKIKO
    }
}
