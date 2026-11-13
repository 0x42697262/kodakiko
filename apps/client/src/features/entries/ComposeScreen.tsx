import { useNavigation, useRouter, type Href } from 'expo-router';
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { Image, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { NOTE_MAX_CHARS, TAGS_PER_ENTRY_MAX } from '@kodakiko/contract';
import {
  buildNewEntryMetadata,
  deviceOffsetMinutes,
  earliestTime,
} from '@kodakiko/core';
import { useAppContext } from '../../composition/app-context';
import {
  COMPOSE_FIRST_SELECTION,
  MOVE_LEFT,
  NOTE_COUNTER_THRESHOLD,
  TILE_BORDER_SELECTED,
  TILE_SIZE,
} from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { APP_ROUTES, TABS_ROUTE_NAME } from '../../keys/routes';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Banner } from '../../ui/Banner';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { Title } from '../../ui/Typography';
import { BORDER_WIDTH, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { thumbnailDataUri } from '../capture/thumbnail-uri';
import { TagInput } from '../tags/tag-input';
import { formatEntryTime } from './format-entry-time';
import { LocationRow } from './location-row';
import { TimeEditor, type EntryTime } from './time-editor';

type EntryLocation = NonNullable<ReturnType<typeof buildNewEntryMetadata>['location']>;

export function ComposeScreen() {
  const router = useRouter();
  const navigation = useNavigation();
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const tray = services.captureTray;
  const items = useSyncExternalStore(tray.subscribe, () => tray.items);
  const deviceLocation = useSyncExternalStore(tray.subscribe, () => tray.deviceLocation);
  const [selected, setSelected] = useState(COMPOSE_FIRST_SELECTION);
  const [note, setNote] = useState('');
  const [tags, setTags] = useState<readonly string[]>([]);
  const [override, setOverride] = useState<EntryTime | null>(null);
  const [locationOverride, setLocationOverride] = useState<EntryLocation | null | undefined>(
    undefined,
  );
  const [editingTime, setEditingTime] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failed, setFailed] = useState(false);
  const leaving = useRef(false);

  const thumbnailUris = useMemo(
    () => items.map((item) => thumbnailDataUri(item.thumbnailBytes)),
    [items],
  );

  const draft = useMemo(() => {
    if (items.length === 0) return null;
    const taken = earliestTime(items.map((item) => item.time));
    const metadata = buildNewEntryMetadata({
      media: items.map((item) => item.descriptor),
      times: items.map((item) => item.time),
      note: '',
      tags: [],
      mediaLocations: items.map((item) => item.location?.point ?? null),
      deviceLocation,
      timeZone: null,
      nowUtc: clock.nowIso(),
    });
    return {
      computed: { takenAtUtc: taken.capturedAtUtc, utcOffsetMinutes: taken.utcOffsetMinutes },
      location: metadata.location,
    };
  }, [items, deviceLocation, clock]);

  useEffect(() => {
    setOverride(null);
    setEditingTime(false);
  }, [items]);

  useEffect(() => {
    if (items.length === 0 && !leaving.current) router.replace(APP_ROUTES.capture as Href);
  }, [items.length, router]);

  if (draft === null) return <Screen testID={ENTRY_IDS.composeScreen}>{null}</Screen>;

  const shown = override ?? draft.computed;
  const location = locationOverride === undefined ? draft.location : locationOverride;
  const position = Math.min(selected, items.length - 1);
  const selectedItem = items[position];
  const nowIso = clock.nowIso();
  const counterVisible = note.length >= NOTE_MAX_CHARS * NOTE_COUNTER_THRESHOLD;

  function move(offset: number): void {
    const target = position + offset;
    if (target < 0 || target >= items.length) return;
    tray.reorder(position, target);
    setSelected(target);
  }

  function removeSelected(): void {
    if (selectedItem === undefined) return;
    tray.remove(selectedItem.mediaId).catch(() => undefined);
    setSelected(COMPOSE_FIRST_SELECTION);
  }

  async function save(): Promise<void> {
    setSaving(true);
    setFailed(false);
    leaving.current = true;
    try {
      await services.entries.createFromTray({
        note,
        tags,
        time: override,
        ...(locationOverride === undefined ? {} : { location: locationOverride }),
      });
      services.savedNotice.set(clock.now());
      const firstRoute = navigation.getState()?.routes[0]?.name;
      if (router.canDismiss()) router.dismissAll();
      if (firstRoute !== TABS_ROUTE_NAME) router.replace(APP_ROUTES.root as Href);
    } catch {
      leaving.current = false;
      setFailed(true);
      setSaving(false);
    }
  }

  return (
    <Screen testID={ENTRY_IDS.composeScreen} scroll>
      <Title text={EN['compose.title']} />
      <ScrollView horizontal contentContainerStyle={styles.strip} showsHorizontalScrollIndicator={false}>
        {items.map((item, index) => (
          <Pressable
            key={item.mediaId}
            testID={listTestId(ENTRY_IDS.composeItem, index)}
            accessibilityRole="button"
            accessibilityLabel={
              index === position ? EN['compose.itemSelected'](index + 1) : EN['compose.itemLabel'](index + 1)
            }
            disabled={saving}
            onPress={() => setSelected(index)}
            style={[
              styles.tile,
              { borderColor: index === position ? colors.primary : colors.border },
              index === position && styles.tileSelected,
            ]}
          >
            <Image source={{ uri: thumbnailUris[index] }} style={styles.image} />
          </Pressable>
        ))}
      </ScrollView>
      <View style={styles.row}>
        <Button
          testID={testId('compose.moveLeft')}
          label={EN['compose.moveLeft']}
          variant="secondary"
          disabled={saving || position === 0}
          onPress={() => move(MOVE_LEFT)}
        />
        <Button
          testID={testId('compose.moveRight')}
          label={EN['compose.moveRight']}
          variant="secondary"
          disabled={saving || position >= items.length - 1}
          onPress={() => move(1)}
        />
        <Button
          testID={testId('compose.removeItem')}
          label={EN['compose.removeItem']}
          variant="secondary"
          disabled={saving}
          onPress={removeSelected}
        />
      </View>

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.when']}</Text>
        <Text testID={ENTRY_IDS.composeWhenValue} style={[styles.value, { color: colors.text }]}>
          {formatEntryTime(
            shown.takenAtUtc,
            shown.utcOffsetMinutes,
            nowIso,
            deviceOffsetMinutes(new Date(clock.now())),
          )}
        </Text>
        {editingTime ? (
          <TimeEditor
            value={shown}
            original={draft.computed}
            onDone={(value) => {
              setOverride(value);
              setEditingTime(false);
            }}
          />
        ) : (
          <Button
            testID={testId('compose.change')}
            label={EN['compose.change']}
            variant="secondary"
            disabled={saving}
            onPress={() => setEditingTime(true)}
          />
        )}
      </View>

      {location === null ? null : (
        <View style={styles.section}>
          <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.where']}</Text>
          <LocationRow
            latitude={location.latitude}
            longitude={location.longitude}
            source={location.source}
            {...(saving ? {} : { onRemove: () => setLocationOverride(null) })}
          />
        </View>
      )}

      <View style={styles.section}>
        <TextField
          testID={testId('compose.note')}
          label={EN['compose.note']}
          showLabel={false}
          placeholder={EN['compose.notePlaceholder']}
          value={note}
          onChangeText={setNote}
          multiline
          editable={!saving}
          autoCapitalize="sentences"
          maxLength={NOTE_MAX_CHARS}
        />
        {counterVisible ? (
          <Text testID={ENTRY_IDS.composeNoteCounter} style={[styles.label, { color: colors.textMuted }]}>
            {EN['compose.noteCounter'](note.length, NOTE_MAX_CHARS)}
          </Text>
        ) : null}
      </View>

      <View style={styles.section}>
        <Text style={[styles.label, { color: colors.textMuted }]}>{EN['compose.tags']}</Text>
        <TagInput
          tags={tags}
          onChange={setTags}
          suggestions={services.tagSuggestions.suggest}
          maxTags={TAGS_PER_ENTRY_MAX}
          disabled={saving}
        />
      </View>

      {failed ? (
        <Banner testID={ENTRY_IDS.composeFailure} kind="error" message={EN['errors.generic']} />
      ) : null}
      <View style={styles.row}>
        <Button
          testID={testId('compose.back')}
          label={EN['compose.back']}
          variant="secondary"
          disabled={saving}
          onPress={() => router.back()}
        />
        <Button
          testID={testId('compose.save')}
          label={EN['compose.save']}
          busy={saving}
          onPress={() => void save()}
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  strip: { gap: SPACING.sm, alignItems: 'center' },
  tile: {
    width: TILE_SIZE,
    height: TILE_SIZE,
    borderRadius: RADIUS.sm,
    borderWidth: BORDER_WIDTH,
    overflow: 'hidden',
  },
  tileSelected: { borderWidth: TILE_BORDER_SELECTED },
  image: { width: '100%', height: '100%' },
  row: { flexDirection: 'row', gap: SPACING.md, justifyContent: 'space-between', flexWrap: 'wrap' },
  section: { gap: SPACING.sm },
  label: { fontSize: FONT_SIZE.caption },
  value: { fontSize: FONT_SIZE.headline },
});
