import { deviceOffsetMinutes } from '@kodakiko/core';
import { type EntryMetadataV1 } from '@kodakiko/contract';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { z } from 'zod';
import { useAppContext } from '../../composition/app-context';
import { EDIT_STRIP_TILE, EDIT_TILE_BORDER, MOVE_LEFT } from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Body, Title } from '../../ui/Typography';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { useThumbnail } from '../gallery/thumbnail';
import { Sheet } from '../gallery/sheet';
import { MarkdownView } from '../markdown/markdown-view';
import { TagPicker } from '../tags/tag-picker';
import { diffDraft, draftChanged, draftFromMetadata, type EditDraft } from './edit-draft';
import { formatEntryTime } from './format-entry-time';
import { LocationRow } from './location-row';
import { TimeEditor } from './time-editor';

type Metadata = z.infer<typeof EntryMetadataV1>;

const EDIT_IDS = Object.freeze({ screen: 'edit.screen', item: 'edit.item', discardSheet: 'edit.discardSheet' });

function StripTile({ entryId, mediaId, position, selected, onPress }: {
  readonly entryId: string;
  readonly mediaId: string;
  readonly position: number;
  readonly selected: boolean;
  readonly onPress: () => void;
}) {
  const { colors } = useTheme();
  const uri = useThumbnail(entryId, mediaId);
  return (
    <Pressable
      testID={listTestId(EDIT_IDS.item, position)}
      accessibilityRole="button"
      onPress={onPress}
      style={[styles.tile, { borderColor: selected ? colors.primary : colors.border, backgroundColor: colors.surface }]}
    >
      {uri === null ? null : <Image source={{ uri }} style={styles.image} />}
    </Pressable>
  );
}

export function EntryEditScreen({ entryId }: { readonly entryId: string }) {
  const router = useRouter();
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const [metadata, setMetadata] = useState<Metadata | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  const [selected, setSelected] = useState(0);
  const [preview, setPreview] = useState(false);
  const [editingTime, setEditingTime] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    services.entries
      .readMetadata(entryId)
      .then((value) => {
        if (cancelled) return;
        setMetadata(value);
        setDraft(draftFromMetadata(value));
      })
      .catch(() => router.back());
    return () => {
      cancelled = true;
    };
  }, [entryId, services.entries, router]);

  const diff = useMemo(
    () => (metadata === null || draft === null ? null : diffDraft(metadata, draft)),
    [metadata, draft],
  );

  if (metadata === null || draft === null || diff === null) return <Screen testID={EDIT_IDS.screen}>{null}</Screen>;

  const position = Math.min(selected, draft.mediaIds.length - 1);
  const update = (patch: Partial<EditDraft>) => setDraft({ ...draft, ...patch });
  const changed = draftChanged(diff);
  const original = { takenAtUtc: metadata.takenAtUtc, utcOffsetMinutes: metadata.utcOffsetMinutes };

  function move(offset: number): void {
    const target = position + offset;
    if (draft === null || target < 0 || target >= draft.mediaIds.length) return;
    const next = [...draft.mediaIds];
    const [item] = next.splice(position, 1);
    if (item === undefined) return;
    next.splice(target, 0, item);
    update({ mediaIds: next });
    setSelected(target);
  }

  function remove(): void {
    if (draft === null || draft.mediaIds.length <= 1) return;
    update({ mediaIds: draft.mediaIds.filter((_id, index) => index !== position) });
    setSelected(0);
  }

  function leave(): void {
    if (changed) setDiscarding(true);
    else router.back();
  }

  async function save(): Promise<void> {
    if (diff === null) return;
    if (!changed) {
      router.back();
      return;
    }
    setSaving(true);
    setFailed(false);
    try {
      for (const mediaId of diff.removedMediaIds) await services.entries.removeMedia(entryId, mediaId);
      if (Object.keys(diff.edit).length > 0) await services.entries.edit(entryId, diff.edit);
      router.back();
    } catch {
      setFailed(true);
      setSaving(false);
    }
  }

  return (
    <Screen testID={EDIT_IDS.screen} scroll>
      <Title text={EN['edit.title']} />
      <ScrollView horizontal contentContainerStyle={styles.strip} showsHorizontalScrollIndicator={false}>
        {draft.mediaIds.map((mediaId, index) => (
          <StripTile
            key={mediaId}
            entryId={entryId}
            mediaId={mediaId}
            position={index}
            selected={index === position}
            onPress={() => setSelected(index)}
          />
        ))}
      </ScrollView>
      <View style={styles.row}>
        <Button testID={testId('compose.moveLeft')} label={EN['compose.moveLeft']} variant="secondary" disabled={saving || position === 0} onPress={() => move(MOVE_LEFT)} />
        <Button testID={testId('compose.moveRight')} label={EN['compose.moveRight']} variant="secondary" disabled={saving || position >= draft.mediaIds.length - 1} onPress={() => move(1)} />
        <Button testID={testId('compose.removeItem')} label={EN['compose.removeItem']} variant="secondary" disabled={saving || draft.mediaIds.length <= 1} onPress={remove} />
      </View>
      {draft.mediaIds.length <= 1 ? <Body testID={testId('edit.lastMediaHint')} text={EN['edit.lastMediaHint']} /> : null}

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.when']}</Text>
        <Text testID={ENTRY_IDS.composeWhenValue} style={{ color: colors.text }}>
          {formatEntryTime(draft.time.takenAtUtc, draft.time.utcOffsetMinutes, clock.nowIso(), deviceOffsetMinutes(new Date(clock.now())))}
        </Text>
        {editingTime ? (
          <TimeEditor
            value={draft.time}
            original={original}
            onDone={(value) => {
              update({ time: value });
              setEditingTime(false);
            }}
          />
        ) : (
          <Button testID={testId('compose.change')} label={EN['compose.change']} variant="secondary" disabled={saving} onPress={() => setEditingTime(true)} />
        )}
      </View>

      {draft.location === null ? null : (
        <View style={styles.section}>
          <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.where']}</Text>
          <LocationRow
            latitude={draft.location.latitude}
            longitude={draft.location.longitude}
            source={draft.location.source}
            {...(saving ? {} : { onRemove: () => update({ location: null }) })}
          />
        </View>
      )}

      <TagPicker tags={draft.tags} onChange={(tags) => update({ tags })} />

      <View style={styles.row}>
        <Button testID={testId('edit.write')} label={EN['edit.write']} variant={preview ? 'secondary' : 'primary'} onPress={() => setPreview(false)} />
        <Button testID={testId('edit.preview')} label={EN['edit.preview']} variant={preview ? 'primary' : 'secondary'} onPress={() => setPreview(true)} />
      </View>
      {preview ? (
        <MarkdownView source={draft.note} />
      ) : (
        <TextField
          testID={testId('compose.note')}
          label={EN['compose.note']}
          showLabel={false}
          value={draft.note}
          onChangeText={(note) => update({ note })}
          multiline
          editable={!saving}
          autoCapitalize="sentences"
        />
      )}

      {failed ? <Banner testID={testId('edit.failed')} kind="error" message={EN['edit.failed']} /> : null}
      <View style={styles.row}>
        <Button testID={testId('edit.cancel')} label={EN['edit.cancel']} variant="secondary" disabled={saving} onPress={leave} />
        <Button testID={testId('edit.save')} label={EN['edit.save']} busy={saving} onPress={() => save().catch(() => undefined)} />
      </View>

      {discarding ? (
        <Sheet testID={EDIT_IDS.discardSheet} onClose={() => setDiscarding(false)}>
          <Text style={{ color: colors.text }}>{EN['edit.discardTitle']}</Text>
          <Button testID={testId('edit.discard')} label={EN['edit.discard']} variant="danger" onPress={() => router.back()} />
          <Button testID={testId('edit.keepEditing')} label={EN['edit.keepEditing']} variant="secondary" onPress={() => setDiscarding(false)} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  strip: { gap: SPACING.sm, paddingVertical: SPACING.xs },
  tile: { width: EDIT_STRIP_TILE, height: EDIT_STRIP_TILE, borderRadius: RADIUS.sm, borderWidth: EDIT_TILE_BORDER, overflow: 'hidden' },
  image: { width: '100%', height: '100%' },
  row: { flexDirection: 'row', gap: SPACING.sm, flexWrap: 'wrap' },
  section: { gap: SPACING.xs },
  label: { fontSize: FONT_SIZE.caption },
});
