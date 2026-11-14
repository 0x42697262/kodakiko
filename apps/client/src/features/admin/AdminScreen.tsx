import type { AdminStatsResponse, AdminUserDto, InviteDto } from '@kodakiko/contract';
import { useRouter } from '../../navigation/use-router';
import { useCallback, useEffect, useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { Touchable } from '../../ui/Touchable';
import { ApiError } from '../../adapters/http/http';
import { useAccountState, useAppContext } from '../../composition/app-context';
import { CONFIG_FALLBACK } from '../../constants/auth-constants';
import { listTestId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { formatBytes } from '../format/format-bytes';
import { copyText, shareText } from '../links/clipboard';

type Segment = 'people' | 'invites' | 'server';

const GIB = 1_073_741_824;
const DEFAULT_INVITE_DAYS = '7';
const DATE_CHARS = 10;
const ADMIN_IDS = Object.freeze({
  screen: 'admin.screen',
  segment: 'admin.segment',
  userRow: 'admin.userRow',
  userSheet: 'admin.userSheet',
  quota: 'admin.quota',
  useDefault: 'admin.useDefault',
  role: 'admin.role',
  save: 'admin.save',
  error: 'admin.error',
  newInvite: 'admin.newInvite',
  inviteDays: 'admin.inviteDays',
  createInvite: 'admin.createInvite',
  inviteUrl: 'admin.inviteUrl',
  inviteRow: 'admin.inviteRow',
  revoke: 'admin.revoke',
  stat: 'admin.stat',
});

function refusalText(error: unknown): string {
  if (error instanceof ApiError) {
    const reason = (error.details as { reason?: string } | null)?.reason;
    if (reason === 'selfDemotion') return EN['admin.selfDemotion'];
    if (reason === 'lastAdmin') return EN['admin.lastAdmin'];
  }
  return EN['admin.failed'];
}

function inviteState(invite: InviteDto, now: number): string {
  if (invite.revokedAt !== null) return EN['admin.inviteRevoked'];
  if (invite.redeemedAt !== null)
    return EN['admin.inviteRedeemed'](invite.redeemedByUsername ?? '');
  if (Date.parse(invite.expiresAt) <= now) return EN['admin.inviteExpired'];
  return EN['admin.inviteOpen'];
}

function People() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const defaultQuota = (services.config.get() ?? CONFIG_FALLBACK).defaultQuotaBytes;
  const [users, setUsers] = useState<readonly AdminUserDto[]>([]);
  const [editing, setEditing] = useState<AdminUserDto | null>(null);
  const [quota, setQuota] = useState('');
  const [useDefault, setUseDefault] = useState(true);
  const [role, setRole] = useState<'admin' | 'member'>('member');
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    const all: AdminUserDto[] = [];
    let cursor: string | null = null;
    do {
      const page: Awaited<ReturnType<typeof services.admin.listUsers>> =
        await services.admin.listUsers(cursor);
      all.push(...page.items);
      cursor = page.nextCursor;
    } while (cursor !== null);
    setUsers(all);
  }, [services.admin]);

  useEffect(() => {
    reload().catch(() => setError(EN['admin.failed']));
  }, [reload]);

  function open(user: AdminUserDto): void {
    setEditing(user);
    setUseDefault(user.quotaBytes === null);
    setQuota(user.quotaBytes === null ? '' : String(user.quotaBytes / GIB));
    setRole(user.role);
    setError(null);
  }

  async function save(): Promise<void> {
    if (editing === null) return;
    const quotaBytes = useDefault ? null : Math.round(Number(quota) * GIB);
    try {
      await services.admin.patchUser(editing.id, { quotaBytes, role });
      setEditing(null);
      await reload();
    } catch (caught) {
      setError(refusalText(caught));
    }
  }

  return (
    <View style={styles.section}>
      {error !== null && editing === null ? (
        <Banner testID={ADMIN_IDS.error} kind="error" message={error} />
      ) : null}
      {users.map((user, position) => {
        const limit = user.quotaBytes ?? defaultQuota;
        return (
          <Touchable
            key={user.id}
            testID={listTestId(ADMIN_IDS.userRow, position)}
            accessibilityRole="button"
            onPress={() => open(user)}
            style={[styles.row, { borderColor: colors.border }]}
          >
            <Text style={[styles.name, { color: colors.text }]}>{user.username}</Text>
            <Text style={{ color: colors.textMuted }}>
              {EN['admin.userLine'](
                user.role,
                formatBytes(user.usedBytes),
                formatBytes(limit),
                user.lastActiveAt === null
                  ? EN['admin.never']
                  : user.lastActiveAt.slice(0, DATE_CHARS),
              )}
            </Text>
            <View style={styles.tags}>
              {user.usedBytes > limit ? (
                <Text
                  style={[styles.tag, { color: colors.onPrimary, backgroundColor: colors.danger }]}
                >
                  {EN['admin.overQuota']}
                </Text>
              ) : null}
              {user.status === 'pending_deletion' ? (
                <Text
                  style={[
                    styles.tag,
                    { color: colors.onPrimary, backgroundColor: colors.textMuted },
                  ]}
                >
                  {EN['admin.pendingDeletion']}
                </Text>
              ) : null}
            </View>
          </Touchable>
        );
      })}
      {editing === null ? null : (
        <Sheet testID={ADMIN_IDS.userSheet} onClose={() => setEditing(null)}>
          <Text style={[styles.name, { color: colors.text }]}>{editing.username}</Text>
          <Touchable
            testID={ADMIN_IDS.useDefault}
            accessibilityRole="switch"
            accessibilityState={{ checked: useDefault }}
            onPress={() => setUseDefault(!useDefault)}
          >
            <Text style={{ color: colors.text }}>
              {useDefault ? '\u2611' : '\u2610'} {EN['admin.useDefault']} (
              {formatBytes(defaultQuota)})
            </Text>
          </Touchable>
          {useDefault ? null : (
            <TextField
              testID={ADMIN_IDS.quota}
              label={EN['admin.quotaField']}
              value={quota}
              onChangeText={setQuota}
              keyboardType="decimal-pad"
            />
          )}
          <View style={styles.tags}>
            <Button
              testID={`${ADMIN_IDS.role}.member`}
              label={EN['admin.roleMember']}
              variant={role === 'member' ? 'primary' : 'secondary'}
              onPress={() => setRole('member')}
            />
            <Button
              testID={`${ADMIN_IDS.role}.admin`}
              label={EN['admin.roleAdmin']}
              variant={role === 'admin' ? 'primary' : 'secondary'}
              onPress={() => setRole('admin')}
            />
          </View>
          {error === null ? null : <Banner testID={ADMIN_IDS.error} kind="error" message={error} />}
          <Button
            testID={ADMIN_IDS.save}
            label={EN['admin.save']}
            onPress={() => save().catch(() => undefined)}
          />
        </Sheet>
      )}
    </View>
  );
}

function Invites() {
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const [invites, setInvites] = useState<readonly InviteDto[]>([]);
  const [creating, setCreating] = useState(false);
  const [days, setDays] = useState(DEFAULT_INVITE_DAYS);
  const [role, setRole] = useState<'admin' | 'member'>('member');
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    services.admin
      .listInvites()
      .then((list) => setInvites(list.items))
      .catch(() => setError(EN['admin.failed']));
  }, [services.admin]);

  useEffect(reload, [reload]);

  async function create(): Promise<void> {
    try {
      const created = await services.admin.createInvite({ role, expiresInDays: Number(days) });
      setUrl(created.inviteUrl);
      reload();
    } catch {
      setError(EN['admin.failed']);
    }
  }

  return (
    <View style={styles.section}>
      <Button
        testID={ADMIN_IDS.newInvite}
        label={EN['admin.newInvite']}
        onPress={() => {
          setCreating(true);
          setUrl(null);
        }}
      />
      {error === null ? null : <Banner testID={ADMIN_IDS.error} kind="error" message={error} />}
      {invites.map((invite, position) => {
        const state = inviteState(invite, clock.now());
        return (
          <View
            key={invite.id}
            testID={listTestId(ADMIN_IDS.inviteRow, position)}
            style={[styles.row, { borderColor: colors.border }]}
          >
            <Text style={{ color: colors.text }}>
              {invite.role} · {state} · {invite.expiresAt.slice(0, DATE_CHARS)}
            </Text>
            {state === EN['admin.inviteOpen'] ? (
              <Button
                testID={listTestId(ADMIN_IDS.revoke, position)}
                label={EN['admin.revoke']}
                variant="danger"
                onPress={() =>
                  services.admin
                    .revokeInvite(invite.id)
                    .then(reload)
                    .catch(() => setError(EN['admin.failed']))
                }
              />
            ) : null}
          </View>
        );
      })}
      {creating ? (
        <Sheet testID="admin.inviteSheet" onClose={() => setCreating(false)}>
          {url === null ? (
            <>
              <View style={styles.tags}>
                <Button
                  testID={`${ADMIN_IDS.role}.member`}
                  label={EN['admin.roleMember']}
                  variant={role === 'member' ? 'primary' : 'secondary'}
                  onPress={() => setRole('member')}
                />
                <Button
                  testID={`${ADMIN_IDS.role}.admin`}
                  label={EN['admin.roleAdmin']}
                  variant={role === 'admin' ? 'primary' : 'secondary'}
                  onPress={() => setRole('admin')}
                />
              </View>
              <TextField
                testID={ADMIN_IDS.inviteDays}
                label={EN['admin.expiryDays']}
                value={days}
                onChangeText={setDays}
                keyboardType="number-pad"
              />
              <Button
                testID={ADMIN_IDS.createInvite}
                label={EN['admin.create']}
                onPress={() => create().catch(() => undefined)}
              />
            </>
          ) : (
            <>
              <Text testID={ADMIN_IDS.inviteUrl} selectable style={{ color: colors.text }}>
                {url}
              </Text>
              <Body text={EN['admin.inviteNote']} />
              <Button
                testID="admin.copyInvite"
                label={Platform.OS === 'web' ? EN['links.copy'] : EN['links.share']}
                onPress={() =>
                  (Platform.OS === 'web' ? copyText(url) : shareText(url)).catch(() => undefined)
                }
              />
            </>
          )}
        </Sheet>
      ) : null}
    </View>
  );
}

function Server() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const [stats, setStats] = useState<AdminStatsResponse | null>(null);
  const config = services.config.get();
  useEffect(() => {
    services.admin
      .stats()
      .then(setStats)
      .catch(() => undefined);
  }, [services.admin]);
  const rows: readonly (readonly [string, string])[] =
    stats === null
      ? []
      : [
          [EN['admin.statUsers'], String(stats.users)],
          [EN['admin.statActive'], String(stats.activeUsers30d)],
          [EN['admin.statPending'], String(stats.pendingDeletion)],
          [EN['admin.statCollections'], String(stats.collections)],
          [EN['admin.statEntries'], String(stats.entries)],
          [EN['admin.statObjects'], String(stats.objects)],
          [EN['admin.statBytes'], formatBytes(stats.totalBytes)],
          [
            EN['admin.statQuota'],
            stats.quotaBytesAllocated === null ? '\u2014' : formatBytes(stats.quotaBytesAllocated),
          ],
          [EN['admin.version'], config?.serverVersion ?? '\u2014'],
          [
            EN['admin.flagSharing'],
            config?.sharingEnabled === true ? EN['admin.on'] : EN['admin.off'],
          ],
          [
            EN['admin.flagLinks'],
            config?.publicLinksEnabled === true ? EN['admin.on'] : EN['admin.off'],
          ],
        ];
  return (
    <View style={styles.section}>
      {rows.map(([label, value], position) => (
        <View key={label} testID={listTestId(ADMIN_IDS.stat, position)} style={styles.statRow}>
          <Text style={{ color: colors.textMuted }}>{label}</Text>
          <Text style={[styles.name, { color: colors.text }]}>{value}</Text>
        </View>
      ))}
    </View>
  );
}

export function AdminScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const state = useAccountState();
  const [segment, setSegment] = useState<Segment>('people');
  const [admin, setAdmin] = useState<boolean | null>(null);
  useEffect(() => {
    services.account
      .isAdmin()
      .then(setAdmin)
      .catch(() => setAdmin(false));
  }, [services.account, state.kind]);

  return (
    <Screen testID={ADMIN_IDS.screen} scroll>
      <IconAction
        testID="shared.back"
        label={EN['shared.back']}
        onPress={() => router.back()}
        icon="back"
        tonal={false}
      />
      <Title text={EN['admin.title']} />
      {admin === false ? <Body testID="admin.notAdmin" text={EN['admin.notAdmin']} /> : null}
      {admin === true ? (
        <>
          <View style={styles.tags}>
            {(['people', 'invites', 'server'] as const).map((value) => (
              <Button
                key={value}
                testID={`${ADMIN_IDS.segment}.${value}`}
                label={EN[`admin.${value}`]}
                variant={segment === value ? 'primary' : 'secondary'}
                onPress={() => setSegment(value)}
              />
            ))}
          </View>
          {segment === 'people' ? <People /> : null}
          {segment === 'invites' ? <Invites /> : null}
          {segment === 'server' ? <Server /> : null}
        </>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  section: { gap: SPACING.sm },
  row: { gap: SPACING.xs, padding: SPACING.md, borderWidth: BORDER_WIDTH, borderRadius: RADIUS.md },
  name: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  tag: { paddingHorizontal: SPACING.sm, borderRadius: RADIUS.sm, fontSize: FONT_SIZE.caption },
  statRow: { flexDirection: 'row', justifyContent: 'space-between', gap: SPACING.md },
});
