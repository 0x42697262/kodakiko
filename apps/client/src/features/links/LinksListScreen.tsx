import { useRouter } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { listTestId } from '../../keys/test-ids';
import type { OwnerLink } from '../../services/links/public-links-service';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Body, Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Sheet } from '../gallery/sheet';
import { copyText, shareText } from './clipboard';
import { LINK_IDS } from './links-ids';

const DAY_MS = 86_400_000;
const HOUR_MS = 3_600_000;

function expiryText(link: OwnerLink, now: number): string {
  if (link.maxViews !== null) return link.viewCount >= link.maxViews ? EN['links.opened'] : EN['links.oneViewUnopened'];
  if (link.expiresAt === null) return EN['links.neverExpires'];
  const remaining = Date.parse(link.expiresAt) - now;
  const days = Math.round(remaining / DAY_MS);
  const when = days >= 1 ? `in ${days} ${days === 1 ? 'day' : 'days'}` : `in ${Math.max(1, Math.round(remaining / HOUR_MS))} h`;
  return EN['links.expiresIn'](when);
}

export function LinksListScreen() {
  const router = useRouter();
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const [links, setLinks] = useState<readonly OwnerLink[] | null>(null);
  const [revoking, setRevoking] = useState<OwnerLink | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    services.links
      .list()
      .then(setLinks)
      .catch(() => setError(EN['errors.sharingFailed']));
  }, [services.links]);

  useEffect(reload, [reload]);

  return (
    <Screen testID={LINK_IDS.listScreen} scroll>
      <Button testID="shared.back" label={EN['shared.back']} variant="secondary" onPress={() => router.back()} />
      <Title text={EN['links.listTitle']} />
      {error === null ? null : <Banner testID={LINK_IDS.error} kind="error" message={error} />}
      {links !== null && links.length === 0 ? <Body testID="links.listEmpty" text={EN['links.listEmpty']} /> : null}
      {(links ?? []).map((link, position) => (
        <View key={link.linkId} testID={listTestId(LINK_IDS.row, position)} style={[styles.row, { borderColor: colors.border }]}>
          <Text style={[styles.title, { color: colors.text }]}>{link.title}</Text>
          <Text style={{ color: colors.textMuted }}>
            {expiryText(link, clock.now())} · {EN['links.views'](link.viewCount)}
          </Text>
          <View style={styles.actions}>
            <Button
              testID={listTestId(LINK_IDS.copyRow, position)}
              label={Platform.OS === 'web' ? EN['links.copyLink'] : EN['links.share']}
              variant="secondary"
              onPress={() => (Platform.OS === 'web' ? copyText(link.url) : shareText(link.url)).catch(() => undefined)}
            />
            <Button testID={listTestId(LINK_IDS.revokeRow, position)} label={EN['links.revoke']} variant="danger" onPress={() => setRevoking(link)} />
          </View>
        </View>
      ))}
      {revoking === null ? null : (
        <Sheet testID="links.revokeSheet" onClose={() => setRevoking(null)}>
          <Text style={{ color: colors.text }}>{EN['links.revokeConfirm']}</Text>
          <Button
            testID={LINK_IDS.revokeConfirm}
            label={EN['links.revokeAction']}
            variant="danger"
            onPress={() => {
              const target = revoking;
              setRevoking(null);
              services.links
                .revoke(target.linkId)
                .then(reload)
                .catch(() => setError(EN['errors.sharingFailed']));
            }}
          />
        </Sheet>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  row: { gap: SPACING.xs, padding: SPACING.md, borderWidth: BORDER_WIDTH, borderRadius: RADIUS.md },
  title: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  actions: { flexDirection: 'row', gap: SPACING.sm, flexWrap: 'wrap' },
});
