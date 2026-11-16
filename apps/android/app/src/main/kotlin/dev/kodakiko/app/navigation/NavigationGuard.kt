package dev.kodakiko.app.navigation

class NavigationGuard(private val now: () -> Long) {
    private var lastKey: Any? = null
    private var lastAllowed: Long? = null

    fun allow(key: Any): Boolean {
        val current = now()
        val previous = lastAllowed
        if (key == lastKey && previous != null && current - previous < NavigationConstants.NAVIGATION_LOCK_MS) return false
        lastKey = key
        lastAllowed = current
        return true
    }
}
