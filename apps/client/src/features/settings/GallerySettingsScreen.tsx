import { GALLERY_COLUMNS_MAX } from '../../constants/ui-settings-constants';
import { EN } from '../../text/en';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import type { PreferenceItem } from '../../ui/preferences/preference-types';
import { SettingsPage } from './settings-ui';
import { useDeviceSettings } from './use-device-settings';

const COLUMN_OPTIONS = Array.from({ length: GALLERY_COLUMNS_MAX + 1 }, (_, columns) => ({
  value: columns,
  label: columns === 0 ? EN['prefs.columnsAuto'] : String(columns),
}));

export function galleryDisplayItems(
  settings: ReturnType<typeof useDeviceSettings>[0],
  update: ReturnType<typeof useDeviceSettings>[1],
): PreferenceItem[] {
  const badges = settings.galleryBadges;
  const badge = (key: keyof typeof badges, title: string): PreferenceItem => ({
    kind: 'switch',
    key: `badge-${key}`,
    testID: `settings.badge.${key}`,
    title,
    value: badges[key],
    onChange: (on) => update({ galleryBadges: { ...badges, [key]: on } }),
  });
  return [
    {
      kind: 'choice',
      key: 'displayMode',
      testID: 'settings.displayMode',
      icon: 'gallery',
      title: EN['prefs.displayMode'],
      value: settings.galleryDisplayMode,
      options: [
        { value: 'comfortableGrid', label: EN['prefs.modeComfortable'] },
        { value: 'compactGrid', label: EN['prefs.modeCompact'] },
        { value: 'photoGrid', label: EN['prefs.modePhoto'] },
        { value: 'list', label: EN['prefs.modeList'] },
      ],
      onChange: (value) => update({ galleryDisplayMode: value as typeof settings.galleryDisplayMode }),
    },
    {
      kind: 'choice',
      key: 'columnsPortrait',
      testID: 'settings.columnsPortrait',
      title: EN['prefs.columnsPortrait'],
      value: settings.galleryColumnsPortrait,
      options: COLUMN_OPTIONS,
      onChange: (value) => update({ galleryColumnsPortrait: Number(value) }),
    },
    {
      kind: 'choice',
      key: 'columnsLandscape',
      testID: 'settings.columnsLandscape',
      title: EN['prefs.columnsLandscape'],
      value: settings.galleryColumnsLandscape,
      options: COLUMN_OPTIONS,
      onChange: (value) => update({ galleryColumnsLandscape: Number(value) }),
    },
    { kind: 'switch', key: 'groupByDay', testID: 'settings.groupByDay', title: EN['prefs.groupByDay'], value: settings.galleryGroupByDay, onChange: (galleryGroupByDay) => update({ galleryGroupByDay }) },
    { kind: 'header', key: 'badges', title: EN['prefs.badges'] },
    badge('video', EN['prefs.badgeVideo']),
    badge('photoCount', EN['prefs.badgePhotoCount']),
    badge('note', EN['prefs.badgeNote']),
    badge('pending', EN['prefs.badgePending']),
    badge('keptOffline', EN['prefs.badgeKeptOffline']),
    { kind: 'header', key: 'tabs', title: EN['prefs.tabs'] },
    { kind: 'switch', key: 'tagTabs', testID: 'settings.showTagTabs', title: EN['prefs.showTagTabs'], value: settings.galleryShowTagTabs, onChange: (galleryShowTagTabs) => update({ galleryShowTagTabs }) },
    { kind: 'switch', key: 'counts', testID: 'settings.showCounts', title: EN['prefs.showCounts'], value: settings.galleryShowCounts, onChange: (galleryShowCounts) => update({ galleryShowCounts }) },
  ];
}

export function GallerySettingsScreen() {
  const [settings, update] = useDeviceSettings();
  return (
    <SettingsPage testID="settings.galleryScreen" title={EN['prefs.gallery']} padded={false}>
      <PreferenceList
        items={[
          ...galleryDisplayItems(settings, update),
          { kind: 'header', key: 'other', title: EN['prefs.gallery'] },
          { kind: 'switch', key: 'onThisDay', testID: 'settings.onThisDay', title: EN['prefs.onThisDay'], value: settings.onThisDay, onChange: (onThisDay) => update({ onThisDay }) },
          { kind: 'switch', key: 'confirmTrash', testID: 'settings.confirmTrash', title: EN['prefs.confirmTrash'], value: settings.confirmTrash, onChange: (confirmTrash) => update({ confirmTrash }) },
          { kind: 'switch', key: 'hideShared', testID: 'settings.hideShared', title: EN['prefs.hideShared'], value: settings.hideShared, onChange: (hideShared) => update({ hideShared }) },
        ]}
      />
    </SettingsPage>
  );
}
