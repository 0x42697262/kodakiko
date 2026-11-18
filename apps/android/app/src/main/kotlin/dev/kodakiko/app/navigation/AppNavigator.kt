package dev.kodakiko.app.navigation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import dev.kodakiko.app.log.AppLogConstants
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.service.account.AccountState
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.lock.ReturnTo
import dev.kodakiko.ui.core.navigation.Route
import dev.kodakiko.ui.core.navigation.RouteNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppNavigator(
    private val accountState: Observable<AccountState>,
    private val returnTo: Observable<String?>,
    private val entryExists: (String) -> Boolean,
    private val startScreen: () -> StartScreen,
    clock: () -> Long,
    private val prepareHome: (suspend () -> Unit)? = null,
    private val onNavigate: (String, Route, Int) -> Unit = { _, _, _ -> },
) : RouteNavigator {
    private val guard = NavigationGuard(clock)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var voyager: Navigator? by mutableStateOf(null)

    var currentTab: Route.Tab by mutableStateOf(startTab())
        private set

    var lastTransition: RouteTransition? by mutableStateOf(null)
        private set

    var securing: Boolean by mutableStateOf(false)
        private set

    override val routes: List<Route>
        get() = (voyager?.items ?: initialScreens()).map { routeOf(it) }

    val canGoBack: Boolean
        get() {
            val navigator = voyager ?: return false
            return navigator.canPop || (navigator.lastItem is HomeScreen && currentTab != startTab())
        }

    internal fun initialScreens(): List<Screen> {
        val state = accountState.value
        return if (state is AccountState.Unlocked) listOf(HomeScreen) else listOf(RouteScreen(RouteGuards.routeForState(state, startScreen())))
    }

    internal fun homeInStack(): Boolean = voyager?.items?.any { it is HomeScreen } ?: true

    internal fun attach(navigator: Navigator) {
        voyager = navigator
    }

    internal fun detach(navigator: Navigator) {
        if (voyager === navigator) voyager = null
    }

    internal fun bind(scope: CoroutineScope): () -> Unit {
        var previous = accountState.value
        reconcile(previous, scope)
        val unsubscribe = accountState.subscribe { next ->
            onMain {
                val before = previous
                previous = next
                onStateChanged(before, next, scope)
            }
        }
        return unsubscribe
    }

    override fun push(route: Route) {
        val target = RouteGuards.resolve(route, startScreen())
        if (!allowed(target)) return
        if (!guard.allow(target)) return
        val navigator = voyager ?: return
        if (target is Route.Tab) {
            showTab(navigator, target)
            return
        }
        lastTransition = RouteTransition(RouteTransitions.kindOf(target), forward = true)
        navigator.push(RouteScreen(target))
        onNavigate(AppLogConstants.ACTION_PUSH, target, navigator.items.size)
    }

    override fun pop(): Boolean {
        val navigator = voyager ?: return false
        if (navigator.canPop) {
            lastTransition = RouteTransition(RouteTransitions.kindOf(routeOf(navigator.lastItem)), forward = false)
            navigator.pop()
            guard.reset()
            onNavigate(AppLogConstants.ACTION_POP, routeOf(navigator.lastItem), navigator.items.size)
            return true
        }
        val start = startTab()
        if (navigator.lastItem is HomeScreen && currentTab != start) {
            currentTab = start
            return true
        }
        return false
    }

    override fun replace(route: Route) {
        val target = RouteGuards.resolve(route, startScreen())
        if (!allowed(target)) return
        val navigator = voyager ?: return
        securing = false
        if (target is Route.Tab) {
            showTab(navigator, target)
            return
        }
        lastTransition = RouteTransition(RouteTransitions.kindOf(target), forward = true)
        navigator.replace(RouteScreen(target))
        onNavigate(AppLogConstants.ACTION_REPLACE, target, navigator.items.size)
    }

    override fun replaceAll(route: Route) {
        val target = RouteGuards.resolve(route, startScreen())
        if (!allowed(target)) return
        val navigator = voyager ?: return
        securing = false
        lastTransition = RouteTransition(RouteTransitions.kindOf(target), forward = true)
        if (target is Route.Tab) {
            currentTab = target
            navigator.replaceAll(HomeScreen)
        } else {
            navigator.replaceAll(RouteScreen(target))
        }
        onNavigate(AppLogConstants.ACTION_REPLACE_ALL, target, navigator.items.size)
    }

    fun showSecuring() {
        securing = true
    }

    fun dismissSecuring() {
        securing = false
    }

    fun selectTab(tab: Route.Tab) {
        currentTab = tab
        onNavigate(AppLogConstants.ACTION_TAB, tab, voyager?.items?.size ?: 0)
    }

    private fun startTab(): Route.Tab = RouteGuards.startRoute(startScreen())

    private fun allowed(route: Route): Boolean = !RouteGuards.requiresUnlock(route) || accountState.value is AccountState.Unlocked

    private fun routeOf(screen: Screen): Route = (screen as? RouteScreen)?.route ?: currentTab

    private fun showTab(navigator: Navigator, tab: Route.Tab) {
        if (navigator.items.firstOrNull() is HomeScreen) {
            if (navigator.canPop) {
                lastTransition = RouteTransition(RouteTransitions.kindOf(routeOf(navigator.lastItem)), forward = false)
                navigator.popUntilRoot()
            }
            currentTab = tab
        } else {
            lastTransition = RouteTransition(RouteTransitions.kindOf(tab), forward = true)
            currentTab = tab
            navigator.replaceAll(HomeScreen)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun reconcile(state: AccountState, scope: CoroutineScope) {
        val current = routes
        if (state is AccountState.Unlocked) {
            if (current.size == 1 && current.single() in STATE_ROUTES) afterHomePrepared(scope) { showHome() }
            return
        }
        if (current.firstOrNull() != RouteGuards.routeForState(state, startScreen()) || current.any(RouteGuards::requiresUnlock)) showStateRoute(state)
    }

    private fun showHome() {
        val navigator = voyager ?: return
        securing = false
        val tab = startTab()
        lastTransition = RouteTransition(RouteTransitions.kindOf(tab), forward = true)
        currentTab = tab
        navigator.replaceAll(HomeScreen)
    }

    private fun onStateChanged(previous: AccountState, next: AccountState, scope: CoroutineScope) {
        if (previous is AccountState.Unlocked && next is AccountState.Locked) rememberCurrentRoute()
        if (next !is AccountState.Locked && next !is AccountState.Unlocked) returnTo.value = null
        if (next is AccountState.Unlocked) {
            if (previous is AccountState.Locked) restoreAfterUnlock(scope)
            return
        }
        val start = startScreen()
        val target = RouteGuards.routeForState(next, start)
        if (RouteGuards.routeForState(previous, start) != target || routes.any(RouteGuards::requiresUnlock)) showStateRoute(next)
    }

    private fun showStateRoute(state: AccountState) {
        val navigator = voyager ?: return
        securing = false
        val target = RouteGuards.routeForState(state, startScreen())
        lastTransition = RouteTransition(RouteTransitions.kindOf(target), forward = true)
        navigator.replaceAll(RouteScreen(target))
    }

    private fun rememberCurrentRoute() {
        val path = routes.lastOrNull()?.path ?: return
        if (ReturnTo.isProtectedRoute(path)) returnTo.value = path
    }

    private fun restoreAfterUnlock(scope: CoroutineScope) {
        val remembered = returnTo.value
        returnTo.value = null
        val entryId = remembered?.let(ReturnTo::entryIdOfRoute)
        if (entryId == null) {
            afterHomePrepared(scope) { applyRestore(remembered, entryExists = false) }
            return
        }
        scope.launch {
            prepareHome?.invoke()
            val exists = withContext(Dispatchers.IO) { runCatching { entryExists(entryId) }.getOrDefault(false) }
            applyRestore(remembered, exists)
        }
    }

    private fun afterHomePrepared(scope: CoroutineScope, show: () -> Unit) {
        val prepare = prepareHome
        if (prepare == null) {
            show()
            return
        }
        scope.launch {
            prepare()
            if (accountState.value is AccountState.Unlocked) show()
        }
    }

    private fun applyRestore(remembered: String?, entryExists: Boolean) {
        if (accountState.value !is AccountState.Unlocked) return
        val navigator = voyager ?: return
        securing = false
        val next = ReturnTo.nextRouteAfterUnlock(remembered, entryExists)
        val restored = if (next == remembered) Route.fromPath(next) else null
        val start = startTab()
        if (restored == null || restored is Route.Tab) {
            val tab = (restored as? Route.Tab) ?: start
            lastTransition = RouteTransition(RouteTransitions.kindOf(tab), forward = true)
            currentTab = tab
            navigator.replaceAll(HomeScreen)
        } else {
            lastTransition = RouteTransition(RouteTransitions.kindOf(restored), forward = true)
            currentTab = start
            navigator.replaceAll(listOf(HomeScreen, RouteScreen(restored)))
        }
    }

    private companion object {
        val STATE_ROUTES: Set<Route> = setOf(Route.Welcome, Route.Lock, Route.CancelDeletion)
    }
}
