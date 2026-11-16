package dev.kodakiko.app.navigation

import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.lock.ReturnTo
import dev.kodakiko.ui.core.navigation.Route

object RouteGuards {
    private val openBeforeUnlock: Set<Route> = setOf(Route.LocalSetup)

    fun startRoute(startScreen: StartScreen): Route.Tab = when (startScreen) {
        StartScreen.GALLERY -> Route.Gallery
        StartScreen.CALENDAR -> Route.Calendar
    }

    fun routeForState(state: AccountState, startScreen: StartScreen): Route = when (state) {
        AccountState.NoServer, is AccountState.SignedOut -> Route.Welcome
        AccountState.Locked -> Route.Lock
        AccountState.PendingDeletion -> Route.CancelDeletion
        is AccountState.Unlocked -> startRoute(startScreen)
    }

    fun requiresUnlock(route: Route): Boolean = route !in openBeforeUnlock && ReturnTo.isProtectedRoute(route.path)

    fun redirect(route: Route, state: AccountState, startScreen: StartScreen): Route? =
        if (requiresUnlock(route) && state !is AccountState.Unlocked) routeForState(state, startScreen) else null

    fun resolve(route: Route, startScreen: StartScreen): Route = if (route == Route.Home) startRoute(startScreen) else route
}
