package dev.kodakiko.service.account

import dev.kodakiko.account.AccountKeys

sealed interface AccountState {
    data object NoServer : AccountState

    data class SignedOut(val username: String? = null) : AccountState

    data object Locked : AccountState

    data class Unlocked(val keys: AccountKeys, val userId: String, val username: String) : AccountState

    data object PendingDeletion : AccountState
}
