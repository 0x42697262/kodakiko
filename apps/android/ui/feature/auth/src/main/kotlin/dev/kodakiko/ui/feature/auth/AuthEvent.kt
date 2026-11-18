package dev.kodakiko.ui.feature.auth

import dev.kodakiko.ui.core.navigation.Route

sealed interface AuthEvent {
    data class Push(val route: Route) : AuthEvent

    data class ReplaceAll(val route: Route) : AuthEvent
}
