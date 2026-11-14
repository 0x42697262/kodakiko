import type { EntryMetadataV1 } from '@kodakiko/contract';
import { Image } from 'expo-image';
import { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Chip } from 'react-native-paper';
import type { z } from 'zod';
import { canShareFiles, shareFiles, type SharedFile } from '../../adapters/share-files/share-files';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Icon } from '../../ui/icons';
import { RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { Touchable } from '../../ui/Touchable';
import { fileNameOf } from '../entries/media-file-name';
import { formatBytes } from '../format/format-bytes';
import { UnderlineTabs } from '../gallery/sheet-tabs';
import { useThumbnail } from '../gallery/thumbnail';
import { shareSelection, shareText, type ShareTextOption } from './share-picker-model';
import { ShareEntriesSheet } from './ShareEntriesSheet';

type Descriptor = z.infer<typeof EntryMetadataV1>['media'][number];
type PickItem = { readonly entryId: string; readonly media: Descriptor; readonly position: number; readonly note: string };

const TILE = 88;
const CHECK = 22;
const RELEASE_DELAY_MS = 60_000;
const TEXT_OPTIONS: readonly { readonly key: ShareTextOption; readonly label: string }[] = [
  { key: 'none', label: EN['share.textNone'] },
  { key: 'captions', label: EN['share.textCaptions'] },
  { key: 'captionsAndNote', label: EN['share.textCaptionsAndNote'] },
];

export async function shareOriginals(
  open: (entryId: string, mediaId: string) => Promise<{ readonly uri: string; readonly mimeType: string; readonly release: () => Promise<void> } | 'missing'>,
  items: readonly PickItem[],
  text: string | null,
): Promise<void> {
  const refs: { readonly release: () => Promise<void> }[] = [];
  const files: SharedFile[] = [];
  try {
    for (const item of items) {
      const ref = await open(item.entryId, item.media.mediaId);
      if (ref === 'missing') continue;
      refs.push(ref);
      files.push({ uri: ref.uri, mimeType: ref.mimeType, fileName: fileNameOf(item.media, item.position) });
    }
    if (files.length > 0) await shareFiles(files, text);
  } finally {
    setTimeout(() => {
      for (const ref of refs) ref.release().catch(() => undefined);
    }, RELEASE_DELAY_MS);
  }
}

function PickTile({ item, index, checked, onToggle }: { readonly item: PickItem; readonly index: number; readonly checked: boolean; readonly onToggle: () => void }) {
  const { colors } = useTheme();
  const uri = useThumbnail(item.entryId, item.media.mediaId);
  return (
    <Touchable
      testID={`share.item.${index}`}
      role="checkbox"
      aria-checked={checked}
      accessibilityLabel={EN['share.itemLabel'](index + 1, checked)}
      onPress={onToggle}
      style={[styles.tile, { backgroundColor: colors.surface, borderColor: checked ? colors.primary : colors.border }]}
    >
      {uri === null ? null : <Image source={{ uri }} style={StyleSheet.absoluteFill} contentFit="cover" />}
      <View style={styles.check}>
        <Icon name={checked ? 'triInclude' : 'triOff'} size={CHECK} color={checked ? colors.primary : colors.onPrimary} />
      </View>
    </Touchable>
  );
}

function SharePicker({ entryIds }: { readonly entryIds: readonly string[] }) {
  const { services } = useAppContext();
  const [items, setItems] = useState<readonly PickItem[] | null>(null);
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [option, setOption] = useState<ShareTextOption>('none');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const supported = canShareFiles();

  useEffect(() => {
    let cancelled = false;
    Promise.all(entryIds.map((entryId) => services.entries.readMetadata(entryId).then((metadata) => ({ entryId, metadata }))))
      .then((loaded) => {
        if (cancelled) return;
        const next = loaded.flatMap(({ entryId, metadata }) => metadata.media.map((media, position) => ({ entryId, media, position, note: metadata.note })));
        setItems(next);
        setChosen(new Set(next.map((item) => item.media.mediaId)));
      })
      .catch(() => setFailed(true));
    return () => {
      cancelled = true;
    };
  }, [entryIds, services.entries]);

  const model = useMemo(() => (items ?? []).map((item) => ({ mediaId: item.media.mediaId, bytes: item.media.originalPlaintextBytes, caption: item.media.caption })), [items]);
  if (items === null) return null;
  const selection = shareSelection(model, chosen);
  const notes = [...new Set(items.filter((item) => chosen.has(item.media.mediaId)).map((item) => item.note))].join('\n\n');

  async function send(): Promise<void> {
    if (items === null) return;
    setBusy(true);
    setFailed(false);
    try {
      await shareOriginals(services.originals.open, items.filter((item) => chosen.has(item.media.mediaId)), shareText(option, model, chosen, notes));
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <View testID="share.picker" style={styles.picker}>
      <Banner testID="share.leavesEncryption" kind="info" message={EN['share.leavesEncryption']} />
      <View style={styles.grid}>
        {items.map((item, index) => (
          <PickTile
            key={item.media.mediaId}
            item={item}
            index={index}
            checked={chosen.has(item.media.mediaId)}
            onToggle={() => {
              const next = new Set(chosen);
              if (next.has(item.media.mediaId)) next.delete(item.media.mediaId);
              else next.add(item.media.mediaId);
              setChosen(next);
            }}
          />
        ))}
      </View>
      <View style={styles.chips}>
        {TEXT_OPTIONS.map((choice) => (
          <Chip key={choice.key} testID={`share.text.${choice.key}`} selected={option === choice.key} showSelectedCheck mode="outlined" onPress={() => setOption(choice.key)}>
            {choice.label}
          </Chip>
        ))}
      </View>
      {selection.warn ? <Banner testID="share.tooBig" kind="warning" message={EN['share.tooBig'](formatBytes(selection.bytes))} /> : null}
      {supported ? null : <Banner testID="share.unsupported" kind="warning" message={EN['share.unsupported']} />}
      {failed ? <Banner testID="share.failed" kind="error" message={EN['share.failed']} /> : null}
      <Button
        testID="share.send"
        label={EN['share.send'](selection.count, formatBytes(selection.bytes))}
        disabled={!supported || selection.count === 0}
        busy={busy}
        onPress={() => {
          send().catch(() => undefined);
        }}
      />
    </View>
  );
}

export function ShareSheet({ entryIds, inKodakiko }: { readonly entryIds: readonly string[]; readonly inKodakiko: boolean }) {
  const { colors } = useTheme();
  const local = useAccountMode() === 'local';
  const kodakiko = inKodakiko && !local;
  const [tab, setTab] = useState<'apps' | 'kodakiko'>(kodakiko ? 'kodakiko' : 'apps');
  return (
    <View style={styles.sheet}>
      {kodakiko ? (
        <UnderlineTabs
          tabs={[
            { key: 'kodakiko', label: EN['share.inKodakiko'] },
            { key: 'apps', label: EN['share.toApps'] },
          ]}
          value={tab}
          onChange={setTab}
        />
      ) : (
        <Text style={{ color: colors.text }}>{EN['share.toApps']}</Text>
      )}
      {tab === 'kodakiko' && kodakiko ? <ShareEntriesSheet entryIds={entryIds} /> : <SharePicker entryIds={entryIds} />}
    </View>
  );
}

const styles = StyleSheet.create({
  sheet: { gap: SPACING.md },
  picker: { gap: SPACING.md },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  tile: { width: TILE, height: TILE, borderRadius: RADIUS.sm, overflow: 'hidden', borderWidth: StyleSheet.hairlineWidth },
  check: { position: 'absolute', top: SPACING.xs, right: SPACING.xs },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
});
