package dev.kodakiko.service.account

import dev.kodakiko.contract.UserDto
import dev.kodakiko.data.db.StorageKeys
import dev.kodakiko.data.net.AccountCalls
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.net.NetworkError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface AccountService {
    val user: Observable<UserDto?>

    suspend fun refreshMe(): UserDto?

    suspend fun isAdmin(): Boolean

    fun clear()
}

class DefaultAccountService(
    private val http: HttpClient,
    private val rowStore: RowStorePort,
    private val ioDispatcher: CoroutineDispatcher,
) : AccountService {
    override val user: Observable<UserDto?> = Observable(null)

    override suspend fun refreshMe(): UserDto? {
        val me = try {
            withContext(ioDispatcher) {
                http.request(AccountCalls.me()).also {
                    AccountStorage.saveRole(rowStore, it.role.wire)
                    AccountStorage.saveDeletionDueAt(rowStore, it.deletionDueAt)
                }
            }
        } catch (failure: NetworkError) {
            return null
        }
        user.value = me
        return me
    }

    override suspend fun isAdmin(): Boolean {
        val role = try {
            withContext(ioDispatcher) { AccountStorage.readString(rowStore, StorageKeys.ACCOUNT_ROLE) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        return role == AccountConstants.ADMIN_ROLE
    }

    override fun clear() {
        user.value = null
    }
}
