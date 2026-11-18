package dev.kodakiko.service.lock

object ReturnTo {
    fun isProtectedRoute(path: String): Boolean = LockRoutes.UNPROTECTED.none { it.containsMatchIn(path) }

    fun entryIdOfRoute(path: String): String? = LockRoutes.ENTRY_PATTERN.find(path)?.groupValues?.get(LockRoutes.ENTRY_ID_GROUP)

    fun nextRouteAfterUnlock(returnTo: String?, entryExists: Boolean): String {
        if (returnTo == null || !isProtectedRoute(returnTo)) return LockRoutes.HOME
        if (entryIdOfRoute(returnTo) != null && !entryExists) return LockRoutes.HOME
        return returnTo
    }
}
