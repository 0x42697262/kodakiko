import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Checkbox, Chip } from 'react-native-paper';
import { GALLERY_COLUMNS_MAX } from '../../constants/ui-settings-constants';
import type { DeviceSettingsValue } from '../../services/settings/device-settings';
import { EN } from '../../text/en';
import { Icon, type IconKey } from '../../ui/icons';
import { useTheme } from '../../ui/theme';
import { FONT_SIZE, MIN_TOUCH_TARGET, SPACING } from '../../ui/theme-constants';

const INDICATOR_HEIGHT = 3;
const DOT_SIZE = 10;
const DOT_ACTIVE_SIZE = 18;
const ICON_SIZE = 24;
const DOT_HIT_WIDTH = 22;

export function UnderlineTabs<Key extends string>({ tabs, value, onChange }: { readonly tabs: readonly { readonly key: Key; readonly label: string }[]; readonly value: Key; readonly onChange: (key: Key) => void }) {
  const { colors } = useTheme();
  return (
    <View style={[styles.tabs, { borderBottomColor: colors.border }]} role="tablist">
      {tabs.map((tab) => {
        const active = tab.key === value;
        return (
          <Pressable key={tab.key} testID={`sheet.tab.${tab.key}`} role="tab" aria-selected={active} onPress={() => onChange(tab.key)} style={styles.tab}>
            <Text style={[styles.tabLabel, { color: active ? colors.primary : colors.textMuted }]}>{tab.label}</Text>
            <View style={[styles.indicator, { backgroundColor: active ? colors.primary : 'transparent' }]} />
          </Pressable>
        );
      })}
    </View>
  );
}

function Heading({ text }: { readonly text: string }) {
  const { colors } = useTheme();
  return <Text style={[styles.heading, { color: colors.textMuted }]}>{text}</Text>;
}

function CheckRow({ testID, label, checked, onChange }: { readonly testID: string; readonly label: string; readonly checked: boolean; readonly onChange: (next: boolean) => void }) {
  return <Checkbox.Item testID={testID} label={label} status={checked ? 'checked' : 'unchecked'} position="leading" labelStyle={styles.checkLabel} onPress={() => onChange(!checked)} />;
}

function DotSlider({ value, onChange }: { readonly value: number; readonly onChange: (value: number) => void }) {
  const { colors } = useTheme();
  const steps = Array.from({ length: GALLERY_COLUMNS_MAX + 1 }, (_, step) => step);
  return (
    <View style={styles.slider}>
      <View style={[styles.track, { backgroundColor: colors.surface }]} />
      {steps.map((step) => (
        <Pressable key={step} testID={`sheet.columns.${step}`} role="radio" aria-checked={step === value} aria-label={step === 0 ? EN['prefs.columnsAuto'] : String(step)} onPress={() => onChange(step)} style={styles.dotHit}>
          <View
            style={{
              width: step === value ? DOT_ACTIVE_SIZE : DOT_SIZE,
              height: step === value ? DOT_ACTIVE_SIZE : DOT_SIZE,
              borderRadius: DOT_ACTIVE_SIZE,
              backgroundColor: step <= value ? colors.primary : colors.border,
            }}
          />
        </Pressable>
      ))}
    </View>
  );
}

type Update = (partial: Partial<DeviceSettingsValue>) => void;

export function DisplayTab({ settings, update, landscape }: { readonly settings: DeviceSettingsValue; readonly update: Update; readonly landscape: boolean }) {
  const { colors } = useTheme();
  const modes: readonly { readonly key: DeviceSettingsValue['galleryDisplayMode']; readonly label: string }[] = [
    { key: 'compactGrid', label: EN['prefs.modeCompact'] },
    { key: 'comfortableGrid', label: EN['prefs.modeComfortable'] },
    { key: 'photoGrid', label: EN['prefs.modePhoto'] },
    { key: 'list', label: EN['prefs.modeList'] },
  ];
  const columns = landscape ? settings.galleryColumnsLandscape : settings.galleryColumnsPortrait;
  const badges = settings.galleryBadges;
  const badge = (key: keyof typeof badges, label: string) => (
    <CheckRow key={key} testID={`settings.badge.${key}`} label={label} checked={badges[key]} onChange={(on) => update({ galleryBadges: { ...badges, [key]: on } })} />
  );
  return (
    <View>
      <Heading text={EN['prefs.displayMode']} />
      <View style={styles.chips}>
        {modes.map((mode) => (
          <Chip key={mode.key} testID={`settings.displayMode.${mode.key}`} selected={settings.galleryDisplayMode === mode.key} showSelectedCheck={false} mode={settings.galleryDisplayMode === mode.key ? 'flat' : 'outlined'} onPress={() => update({ galleryDisplayMode: mode.key })}>
            {mode.label}
          </Chip>
        ))}
      </View>
      {settings.galleryDisplayMode === 'list' ? null : (
        <>
          <View style={styles.sliderHeader}>
            <Heading text={EN['prefs.itemsPerRow']} />
            <Text style={{ color: colors.text }}>{columns === 0 ? EN['prefs.columnsAuto'] : columns}</Text>
          </View>
          <DotSlider value={columns} onChange={(next) => update(landscape ? { galleryColumnsLandscape: next } : { galleryColumnsPortrait: next })} />
        </>
      )}
      <Heading text={EN['prefs.badges']} />
      {badge('favorite', EN['prefs.badgeFavorite'])}
      {badge('video', EN['prefs.badgeVideo'])}
      {badge('photoCount', EN['prefs.badgePhotoCount'])}
      {badge('note', EN['prefs.badgeNote'])}
      {badge('pending', EN['prefs.badgePending'])}
      <Heading text={EN['prefs.tabs']} />
      <CheckRow testID="settings.showTagTabs" label={EN['prefs.showTagTabs']} checked={settings.galleryShowTagTabs} onChange={(galleryShowTagTabs) => update({ galleryShowTagTabs })} />
      <CheckRow testID="settings.showCounts" label={EN['prefs.showCounts']} checked={settings.galleryShowCounts} onChange={(galleryShowCounts) => update({ galleryShowCounts })} />
    </View>
  );
}

export function GroupTab({ settings, update }: { readonly settings: DeviceSettingsValue; readonly update: Update }) {
  const { colors } = useTheme();
  const current = settings.galleryGroupByDay ? settings.galleryGroupBy : 'none';
  const groups: readonly { readonly key: DeviceSettingsValue['galleryGroupBy']; readonly icon: IconKey; readonly label: string }[] = [
    { key: 'day', icon: 'today', label: EN['group.day'] },
    { key: 'month', icon: 'calendar', label: EN['group.month'] },
    { key: 'year', icon: 'statistics', label: EN['group.year'] },
    { key: 'tag', icon: 'tags', label: EN['group.tag'] },
    { key: 'none', icon: 'gallery', label: EN['group.none'] },
  ];
  return (
    <View>
      {groups.map((group) => {
        const active = current === group.key;
        return (
          <Pressable key={group.key} testID={`sheet.group.${group.key}`} role="radio" aria-checked={active} style={styles.groupRow} onPress={() => update({ galleryGroupBy: group.key, galleryGroupByDay: group.key !== 'none' })}>
            <Icon name={group.icon} size={ICON_SIZE} color={active ? colors.primary : colors.textMuted} />
            <Text style={{ color: active ? colors.primary : colors.text, fontSize: FONT_SIZE.body }}>{group.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  tabs: { flexDirection: 'row', borderBottomWidth: StyleSheet.hairlineWidth },
  tab: { flex: 1, alignItems: 'center', paddingTop: SPACING.md, gap: SPACING.sm },
  tabLabel: { fontSize: FONT_SIZE.body, fontWeight: '600' },
  indicator: { height: INDICATOR_HEIGHT, alignSelf: 'stretch', marginHorizontal: SPACING.lg, borderTopLeftRadius: INDICATOR_HEIGHT, borderTopRightRadius: INDICATOR_HEIGHT },
  heading: { fontSize: FONT_SIZE.caption, fontWeight: '600', paddingTop: SPACING.lg, paddingBottom: SPACING.sm },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACING.sm },
  sliderHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-end' },
  slider: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: MIN_TOUCH_TARGET },
  track: { position: 'absolute', left: SPACING.sm, right: SPACING.sm, height: DOT_SIZE, borderRadius: DOT_SIZE },
  dotHit: { minWidth: DOT_HIT_WIDTH, minHeight: MIN_TOUCH_TARGET, alignItems: 'center', justifyContent: 'center' },
  checkLabel: { textAlign: 'left' },
  groupRow: { flexDirection: 'row', alignItems: 'center', gap: SPACING.lg, minHeight: MIN_TOUCH_TARGET },
});
