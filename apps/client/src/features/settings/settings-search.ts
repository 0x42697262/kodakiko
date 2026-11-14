import { EN } from '../../text/en';

export type SettingsIndexEntry = { readonly title: string; readonly section: string; readonly route: string };

const SECTIONS: readonly { readonly section: string; readonly route: string; readonly titles: readonly string[] }[] = [
  {
    section: EN['prefs.appearance'],
    route: '/settings/appearance',
    titles: [EN['prefs.theme'], EN['prefs.dateFormat'], EN['prefs.relativeDates'], EN['prefs.startScreen'], EN['prefs.fullScreen'], EN['prefs.hideBarsOnScroll'], EN['prefs.technicalDetails']],
  },
  {
    section: EN['prefs.gallery'],
    route: '/settings/gallery',
    titles: [
      EN['prefs.displayMode'],
      EN['prefs.columnsPortrait'],
      EN['prefs.columnsLandscape'],
      EN['prefs.groupByDay'],
      EN['prefs.badges'],
      EN['prefs.showTagTabs'],
      EN['prefs.showCounts'],
      EN['prefs.onThisDay'],
      EN['prefs.confirmTrash'],
      EN['prefs.hideShared'],
    ],
  },
  {
    section: EN['prefs.capture'],
    route: '/settings/capture',
    titles: [EN['settings.location'], EN['prefs.startCamera'], EN['prefs.keepScreenOn'], EN['prefs.cameraApp'], EN['prefs.holdToRecord'], EN['prefs.holdMax']],
  },
  {
    section: EN['prefs.security'],
    route: '/settings/security',
    titles: [
      EN['settings.autoLock'],
      EN['settings.keepUnlocked'],
      EN['settings.biometric'],
      EN['settings.encryptThumbnails'],
      EN['prefs.secureScreen'],
      EN['prefs.hideNotePreviews'],
    ],
  },
  {
    section: EN['prefs.dataStorage'],
    route: '/settings/storage',
    titles: [EN['settings.cacheCap'], EN['settings.clearOriginals'], EN['settings.wifiOnly'], EN['settings.largeMeans'], EN['prefs.exportJournal']],
  },
  {
    section: EN['settings.rowSync'],
    route: '/settings/sync',
    titles: [EN['settings.syncNow'], EN['settings.retryFailed']],
  },
  {
    section: EN['settings.rowAccount'],
    route: '/settings/account',
    titles: [EN['settings.changePassword'], EN['settings.showRecovery'], EN['settings.deleteAccount'], EN['settings.securityCode'], EN['server.title']],
  },
  {
    section: EN['settings.rowAbout'],
    route: '/settings/about',
    titles: [EN['settings.appVersion'], EN['settings.licenses']],
  },
];

export const SETTINGS_INDEX: readonly SettingsIndexEntry[] = SECTIONS.flatMap((section) =>
  [section.section, ...section.titles].map((title) => ({ title, section: section.section, route: section.route })),
);

const SPACES = /\s+/;
const ELLIPSIS = /\u2026/g;

export function searchSettings(query: string): readonly SettingsIndexEntry[] {
  const words = query.trim().toLowerCase().split(SPACES).filter((word) => word !== '');
  if (words.length === 0) return [];
  return SETTINGS_INDEX.filter((entry) => {
    const haystack = `${entry.title} ${entry.section}`.replace(ELLIPSIS, '').toLowerCase();
    return words.every((word) => haystack.includes(word));
  });
}
