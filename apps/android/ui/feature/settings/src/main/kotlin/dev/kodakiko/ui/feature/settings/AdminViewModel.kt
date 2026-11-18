package dev.kodakiko.ui.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodakiko.contract.AdminInviteCreateRequest
import dev.kodakiko.contract.AdminStatsResponse
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.AdminUserPatchRequest
import dev.kodakiko.contract.InviteDto
import dev.kodakiko.contract.OptionalValue
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.data.net.ApiError
import dev.kodakiko.js.JsDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

enum class AdminSegment(val wire: String) {
    PEOPLE("people"),
    INVITES("invites"),
    SERVER("server"),
}

enum class AdminError { SELF_DEMOTION, LAST_ADMIN, FAILED }

enum class InviteState { OPEN, REDEEMED, EXPIRED, REVOKED }

fun inviteState(invite: InviteDto, nowMs: Long): InviteState = when {
    invite.revokedAt != null -> InviteState.REVOKED
    invite.redeemedAt != null -> InviteState.REDEEMED
    JsDate.parse(invite.expiresAt) <= nowMs.toDouble() -> InviteState.EXPIRED
    else -> InviteState.OPEN
}

@Immutable
data class AdminUserEditor(val user: AdminUserDto, val useDefault: Boolean, val quota: String, val role: UserRole, val error: AdminError?)

@Immutable
data class AdminInviteEditor(val role: UserRole, val days: String, val url: String?)

@Immutable
data class AdminState(
    val admin: Boolean?,
    val segment: AdminSegment,
    val users: List<AdminUserDto>,
    val defaultQuotaBytes: Long,
    val peopleError: AdminError?,
    val editing: AdminUserEditor?,
    val invites: List<InviteDto>,
    val invitesError: AdminError?,
    val inviteEditor: AdminInviteEditor?,
    val stats: AdminStatsResponse?,
    val config: PublicConfigDto?,
    val nowMs: Long,
)

sealed interface AdminAction {
    data class SelectSegment(val segment: AdminSegment) : AdminAction

    data class OpenUser(val user: AdminUserDto) : AdminAction

    data object CloseUser : AdminAction

    data class SetUseDefault(val on: Boolean) : AdminAction

    data class SetQuota(val value: String) : AdminAction

    data class SetRole(val role: UserRole) : AdminAction

    data object SaveUser : AdminAction

    data object NewInvite : AdminAction

    data class SetInviteRole(val role: UserRole) : AdminAction

    data class SetInviteDays(val value: String) : AdminAction

    data object CreateInvite : AdminAction

    data object ShareInvite : AdminAction

    data object CloseInvite : AdminAction

    data class Revoke(val invite: InviteDto) : AdminAction

    data object Back : AdminAction
}

private fun refusalOf(error: Throwable): AdminError {
    val reason = ((error as? ApiError)?.details as? JsonObject)?.get(SettingsConstants.REFUSAL_REASON) as? JsonPrimitive
    return when (reason?.takeIf { it.isString }?.content) {
        SettingsConstants.SELF_DEMOTION -> AdminError.SELF_DEMOTION
        SettingsConstants.LAST_ADMIN -> AdminError.LAST_ADMIN
        else -> AdminError.FAILED
    }
}

internal fun quotaText(bytes: Long): String {
    val gibibytes = bytes / SettingsConstants.GIB
    val whole = gibibytes.roundToLong()
    return if (whole.toDouble() == gibibytes) whole.toString() else gibibytes.toString()
}

class AdminViewModel(private val deps: AdminDeps) : ViewModel() {
    private val config = deps.settings.config.current()
    private val current = MutableStateFlow(
        AdminState(
            admin = null,
            segment = AdminSegment.PEOPLE,
            users = emptyList(),
            defaultQuotaBytes = config?.defaultQuotaBytes ?: SettingsConstants.DEFAULT_QUOTA_BYTES,
            peopleError = null,
            editing = null,
            invites = emptyList(),
            invitesError = null,
            inviteEditor = null,
            stats = null,
            config = config,
            nowMs = deps.nowMs(),
        ),
    )
    private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

    val events: Flow<SettingsEvent> = channel.receiveAsFlow()

    val state: StateFlow<AdminState> = current.asStateFlow()

    init {
        viewModelScope.launch {
            merge(flowOf(Unit), deps.settings.account.roleChanges).collect { checkAdmin() }
        }
    }

    private suspend fun checkAdmin() {
        val admin = attempt { deps.settings.account.isAdmin() } ?: false
        val before = current.value.admin
        current.update { it.copy(admin = admin) }
        if (admin && before != true) select(current.value.segment)
    }

    fun onAction(action: AdminAction) {
        when (action) {
            is AdminAction.SelectSegment -> select(action.segment)
            is AdminAction.OpenUser -> open(action.user)
            AdminAction.CloseUser -> current.update { it.copy(editing = null) }
            is AdminAction.SetUseDefault -> editUser { it.copy(useDefault = action.on) }
            is AdminAction.SetQuota -> editUser { it.copy(quota = action.value) }
            is AdminAction.SetRole -> editUser { it.copy(role = action.role) }
            AdminAction.SaveUser -> saveUser()
            AdminAction.NewInvite -> current.update {
                it.copy(inviteEditor = AdminInviteEditor(role = UserRole.MEMBER, days = SettingsConstants.DEFAULT_INVITE_DAYS, url = null))
            }
            is AdminAction.SetInviteRole -> editInvite { it.copy(role = action.role) }
            is AdminAction.SetInviteDays -> editInvite { it.copy(days = action.value) }
            AdminAction.CreateInvite -> createInvite()
            AdminAction.ShareInvite -> current.value.inviteEditor?.url?.let(deps.share::share)
            AdminAction.CloseInvite -> current.update { it.copy(inviteEditor = null) }
            is AdminAction.Revoke -> revoke(action.invite)
            AdminAction.Back -> channel.trySend(SettingsEvent.Back)
        }
    }

    private fun editUser(change: (AdminUserEditor) -> AdminUserEditor) {
        current.update { state -> state.editing?.let { state.copy(editing = change(it)) } ?: state }
    }

    private fun editInvite(change: (AdminInviteEditor) -> AdminInviteEditor) {
        current.update { state -> state.inviteEditor?.let { state.copy(inviteEditor = change(it)) } ?: state }
    }

    private fun select(segment: AdminSegment) {
        current.update { it.copy(segment = segment, editing = null, inviteEditor = null, peopleError = null, invitesError = null, nowMs = deps.nowMs()) }
        viewModelScope.launch {
            when (segment) {
                AdminSegment.PEOPLE -> loadUsers()
                AdminSegment.INVITES -> loadInvites()
                AdminSegment.SERVER -> loadStats()
            }
        }
    }

    private suspend fun loadUsers() {
        try {
            val all = mutableListOf<AdminUserDto>()
            var cursor: String? = null
            do {
                val page = deps.admin.listUsers(cursor)
                all += page.items
                cursor = page.nextCursor
            } while (cursor != null)
            current.update { it.copy(users = all) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            current.update { it.copy(peopleError = AdminError.FAILED) }
        }
    }

    private suspend fun loadInvites() {
        val list = attempt { deps.admin.listInvites() }
        current.update { if (list == null) it.copy(invitesError = AdminError.FAILED) else it.copy(invites = list.items, nowMs = deps.nowMs()) }
    }

    private suspend fun loadStats() {
        val stats = attempt { deps.admin.stats() }
        current.update { it.copy(stats = stats, config = deps.settings.config.current()) }
    }

    private fun open(user: AdminUserDto) {
        val quota = user.user.quotaBytes
        current.update {
            it.copy(editing = AdminUserEditor(user = user, useDefault = quota == null, quota = quota?.let(::quotaText).orEmpty(), role = user.user.role, error = null))
        }
    }

    private fun saveUser() {
        val editor = current.value.editing ?: return
        val quota = if (editor.useDefault) null else editor.quota.trim().toDoubleOrNull()?.let { (it * SettingsConstants.GIB).roundToLong() }
        if (!editor.useDefault && quota == null) {
            editUser { it.copy(error = AdminError.FAILED) }
            return
        }
        viewModelScope.launch {
            try {
                deps.admin.patchUser(editor.user.user.id, AdminUserPatchRequest(quotaBytes = OptionalValue.Present(quota), role = editor.role))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                editUser { it.copy(error = refusalOf(error)) }
                return@launch
            }
            current.update { it.copy(editing = null) }
            loadUsers()
        }
    }

    private fun createInvite() {
        val editor = current.value.inviteEditor ?: return
        val days = editor.days.trim().toLongOrNull()
        if (days == null) {
            current.update { it.copy(invitesError = AdminError.FAILED) }
            return
        }
        viewModelScope.launch {
            val created = attempt { deps.admin.createInvite(AdminInviteCreateRequest(role = editor.role, expiresInDays = days)) }
            if (created == null) {
                current.update { it.copy(invitesError = AdminError.FAILED) }
                return@launch
            }
            editInvite { it.copy(url = created.inviteUrl) }
            loadInvites()
        }
    }

    private fun revoke(invite: InviteDto) {
        viewModelScope.launch {
            if (attempt { deps.admin.revokeInvite(invite.id) } == null) {
                current.update { it.copy(invitesError = AdminError.FAILED) }
                return@launch
            }
            loadInvites()
        }
    }
}
