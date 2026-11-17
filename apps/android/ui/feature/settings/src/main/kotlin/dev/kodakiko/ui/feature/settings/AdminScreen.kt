package dev.kodakiko.ui.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kodakiko.contract.AdminUserDto
import dev.kodakiko.contract.InviteDto
import dev.kodakiko.contract.UserRole
import dev.kodakiko.contract.UserStatus
import dev.kodakiko.ui.core.R
import dev.kodakiko.ui.core.TestTags
import dev.kodakiko.ui.core.TestTags.listItem
import dev.kodakiko.ui.core.components.AdaptiveSheet
import dev.kodakiko.ui.core.components.Banner
import dev.kodakiko.ui.core.components.BannerKind
import dev.kodakiko.ui.core.components.FormButton
import dev.kodakiko.ui.core.components.FormTextField
import dev.kodakiko.ui.core.components.Pill
import dev.kodakiko.ui.core.format.formatBytes
import dev.kodakiko.ui.core.util.secondaryItemAlpha
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun AdminScreen(viewModel: AdminViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsEventsEffect(viewModel.events)
    AdminContent(state = state, onAction = viewModel::onAction)
}

@Composable
fun AdminContent(state: AdminState, onAction: (AdminAction) -> Unit) {
    FormPage(title = stringResource(R.string.admin_title), screenTag = TestTags.Admin.SCREEN, onBack = { onAction(AdminAction.Back) }, backTag = TestTags.Shared.BACK) {
        when (state.admin) {
            false -> BodyText(text = stringResource(R.string.admin_not_admin), testTag = TestTags.Admin.NOT_ADMIN)
            true -> AdminBody(state, onAction)
            null -> Unit
        }
    }
    state.editing?.let { UserSheet(it, state.defaultQuotaBytes, onAction) }
    state.inviteEditor?.let { InviteSheet(it, onAction) }
}

@Composable
private fun ColumnScope.AdminBody(state: AdminState, onAction: (AdminAction) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AdminSegment.entries.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = state.segment == segment,
                onClick = { onAction(AdminAction.SelectSegment(segment)) },
                shape = SegmentedButtonDefaults.itemShape(index, AdminSegment.entries.size),
                modifier = Modifier.testTag(TestTags.Admin.SEGMENT + SettingsConstants.BADGE_TAG_SEPARATOR + segment.wire),
                label = { Text(text = stringResource(segment.label())) },
            )
        }
    }
    when (state.segment) {
        AdminSegment.PEOPLE -> People(state, onAction)
        AdminSegment.INVITES -> Invites(state, onAction)
        AdminSegment.SERVER -> ServerStats(state)
    }
}

private fun AdminSegment.label(): Int = when (this) {
    AdminSegment.PEOPLE -> R.string.admin_people
    AdminSegment.INVITES -> R.string.admin_invites
    AdminSegment.SERVER -> R.string.admin_server
}

private fun adminErrorText(error: AdminError): Int = when (error) {
    AdminError.SELF_DEMOTION -> R.string.admin_self_demotion
    AdminError.LAST_ADMIN -> R.string.admin_last_admin
    AdminError.FAILED -> R.string.admin_failed
}

@Composable
private fun ErrorBanner(error: AdminError) {
    Banner(text = stringResource(adminErrorText(error)), kind = BannerKind.ERROR, testTag = TestTags.Admin.ERROR)
}

@Composable
private fun OutlinedBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(SettingsDimens.BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(SettingsDimens.CARD_PADDING), verticalArrangement = Arrangement.spacedBy(SettingsDimens.ROW_SPACING), content = content)
    }
}

@Composable
private fun ColumnScope.People(state: AdminState, onAction: (AdminAction) -> Unit) {
    state.peopleError?.takeIf { state.editing == null }?.let { ErrorBanner(it) }
    state.users.forEachIndexed { position, user ->
        UserRow(user, state.defaultQuotaBytes, Modifier.testTag(listItem(TestTags.Admin.USER_ROW, position)).clickable { onAction(AdminAction.OpenUser(user)) })
    }
}

@Composable
private fun UserRow(item: AdminUserDto, defaultQuotaBytes: Long, modifier: Modifier) {
    val user = item.user
    val limit = user.quotaBytes ?: defaultQuotaBytes
    val lastActive = item.lastActiveAt?.take(SettingsConstants.DATE_CHARS) ?: stringResource(R.string.admin_never)
    OutlinedBox(modifier = modifier) {
        Text(text = user.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(R.string.admin_user_line, user.role.wire, formatBytes(user.usedBytes), formatBytes(limit), lastActive),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.secondaryItemAlpha(),
        )
        val overQuota = user.usedBytes > limit
        val pending = user.status == UserStatus.PENDING_DELETION
        if (overQuota || pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.ITEM_SPACING)) {
                if (overQuota) {
                    Pill(
                        text = stringResource(R.string.admin_over_quota),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (pending) Pill(text = stringResource(R.string.admin_pending_deletion), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RoleChoice(role: UserRole, onRole: (UserRole) -> Unit) {
    val roles = listOf(UserRole.MEMBER to TestTags.Admin.ROLE_MEMBER, UserRole.ADMIN to TestTags.Admin.ROLE_ADMIN)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        roles.forEachIndexed { index, (value, tag) ->
            SegmentedButton(
                selected = role == value,
                onClick = { onRole(value) },
                shape = SegmentedButtonDefaults.itemShape(index, roles.size),
                modifier = Modifier.testTag(tag),
                label = { Text(text = stringResource(if (value == UserRole.ADMIN) R.string.admin_role_admin else R.string.admin_role_member)) },
            )
        }
    }
}

@Composable
private fun SheetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SettingsDimens.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
        content = content,
    )
}

@Composable
private fun UserSheet(editor: AdminUserEditor, defaultQuotaBytes: Long, onAction: (AdminAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(AdminAction.CloseUser) }, modifier = Modifier.testTag(TestTags.Admin.USER_SHEET)) {
        SheetColumn {
            Text(text = editor.user.user.username, style = MaterialTheme.typography.titleMedium)
            ToggleRow(
                title = stringResource(R.string.admin_use_default),
                summary = formatBytes(defaultQuotaBytes),
                checked = editor.useDefault,
                onCheckedChange = { onAction(AdminAction.SetUseDefault(it)) },
                testTag = TestTags.Admin.USE_DEFAULT,
            )
            if (!editor.useDefault) {
                FormTextField(
                    value = editor.quota,
                    onValueChange = { onAction(AdminAction.SetQuota(it)) },
                    label = stringResource(R.string.admin_quota_field),
                    testTag = TestTags.Admin.QUOTA,
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                )
            }
            RoleChoice(editor.role) { onAction(AdminAction.SetRole(it)) }
            editor.error?.let { ErrorBanner(it) }
            FormButton(text = stringResource(R.string.admin_save), onClick = { onAction(AdminAction.SaveUser) }, testTag = TestTags.Admin.SAVE)
        }
    }
}

private fun inviteStateText(state: InviteState): Int = when (state) {
    InviteState.OPEN -> R.string.admin_invite_open
    InviteState.EXPIRED -> R.string.admin_invite_expired
    InviteState.REVOKED -> R.string.admin_invite_revoked
    InviteState.REDEEMED -> R.string.admin_invite_redeemed
}

@Composable
private fun ColumnScope.Invites(state: AdminState, onAction: (AdminAction) -> Unit) {
    FormButton(text = stringResource(R.string.admin_new_invite), onClick = { onAction(AdminAction.NewInvite) }, testTag = TestTags.Admin.NEW_INVITE)
    state.invitesError?.let { ErrorBanner(it) }
    state.invites.forEachIndexed { position, invite -> InviteRow(invite, position, state.nowMs, onAction) }
}

@Composable
private fun InviteRow(invite: InviteDto, position: Int, nowMs: Long, onAction: (AdminAction) -> Unit) {
    val inviteState = inviteState(invite, nowMs)
    val stateText = stringResource(inviteStateText(inviteState), invite.redeemedByUsername.orEmpty())
    val line = listOf(invite.role.wire, stateText, invite.expiresAt.take(SettingsConstants.DATE_CHARS)).joinToString(SettingsConstants.LICENSE_SEPARATOR)
    OutlinedBox(modifier = Modifier.testTag(listItem(TestTags.Admin.INVITE_ROW, position))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SettingsDimens.ITEM_SPACING)) {
            Text(text = line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (inviteState == InviteState.OPEN) {
                DangerButton(
                    text = stringResource(R.string.admin_revoke),
                    onClick = { onAction(AdminAction.Revoke(invite)) },
                    testTag = listItem(TestTags.Admin.REVOKE, position),
                    fill = false,
                )
            }
        }
    }
}

@Composable
private fun InviteSheet(editor: AdminInviteEditor, onAction: (AdminAction) -> Unit) {
    AdaptiveSheet(onDismissRequest = { onAction(AdminAction.CloseInvite) }, modifier = Modifier.testTag(TestTags.Admin.INVITE_SHEET)) {
        SheetColumn {
            val url = editor.url
            if (url == null) {
                RoleChoice(editor.role) { onAction(AdminAction.SetInviteRole(it)) }
                FormTextField(
                    value = editor.days,
                    onValueChange = { onAction(AdminAction.SetInviteDays(it)) },
                    label = stringResource(R.string.admin_expiry_days),
                    testTag = TestTags.Admin.INVITE_DAYS,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                )
                FormButton(text = stringResource(R.string.admin_create), onClick = { onAction(AdminAction.CreateInvite) }, testTag = TestTags.Admin.CREATE_INVITE)
            } else {
                SelectionContainer {
                    Text(text = url, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.Admin.INVITE_URL))
                }
                BodyText(text = stringResource(R.string.admin_invite_note))
                FormButton(text = stringResource(R.string.links_share), onClick = { onAction(AdminAction.ShareInvite) }, testTag = TestTags.Admin.COPY_INVITE)
            }
        }
    }
}

@Composable
private fun ColumnScope.ServerStats(state: AdminState) {
    val stats = state.stats ?: return
    val config = state.config
    val on = stringResource(R.string.admin_on)
    val off = stringResource(R.string.admin_off)
    val rows = listOf(
        R.string.admin_stat_users to stats.users.toString(),
        R.string.admin_stat_active to stats.activeUsers30d.toString(),
        R.string.admin_stat_pending to stats.pendingDeletion.toString(),
        R.string.admin_stat_collections to stats.collections.toString(),
        R.string.admin_stat_entries to stats.entries.toString(),
        R.string.admin_stat_objects to stats.objects.toString(),
        R.string.admin_stat_bytes to formatBytes(stats.totalBytes),
        R.string.admin_stat_quota to (stats.quotaBytesAllocated?.let(::formatBytes) ?: SettingsConstants.UNKNOWN_VALUE),
        R.string.admin_version to (config?.serverVersion ?: SettingsConstants.UNKNOWN_VALUE),
        R.string.admin_flag_sharing to if (config?.sharingEnabled == true) on else off,
        R.string.admin_flag_links to if (config?.publicLinksEnabled == true) on else off,
    )
    rows.forEachIndexed { position, (label, value) ->
        Row(
            modifier = Modifier.fillMaxWidth().testTag(listItem(TestTags.Admin.STAT, position)),
            horizontalArrangement = Arrangement.spacedBy(SettingsDimens.SECTION_SPACING),
        ) {
            Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).secondaryItemAlpha())
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
