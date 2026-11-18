package dev.kodakiko.service.account

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountStateTest {
    @Test
    fun startsWithTheGivenStateAndNotifiesSubscribersOnChange() {
        val state = Observable<AccountState>(AccountState.SignedOut())
        var calls = 0
        val unsubscribe: () -> Unit = state.subscribe { calls += 1 }
        assertEquals(AccountState.SignedOut(), state.value)
        assertEquals(AccountState.SignedOut(), state.flow.value)
        state.value = AccountState.Locked
        assertEquals(AccountState.Locked, state.value)
        assertEquals(AccountState.Locked, state.flow.value)
        assertEquals(1, calls)
        unsubscribe()
        state.value = AccountState.PendingDeletion
        assertEquals(1, calls)
        assertEquals(AccountState.PendingDeletion, state.flow.value)
    }

    @Test
    fun notifiesSubscribersAgainWhenTheSameStateIsSetTwice() {
        val state = Observable<AccountState>(AccountState.Locked)
        var calls = 0
        state.subscribe { calls += 1 }
        state.value = AccountState.Locked
        state.value = AccountState.Locked
        assertEquals(2, calls)
    }
}
