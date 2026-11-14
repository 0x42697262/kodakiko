import Constants from 'expo-constants';
import { useState } from 'react';
import { Platform, StyleSheet, Text } from 'react-native';
import { useAccountMode, useAppContext } from '../../composition/app-context';
import { DEVICE_SETTINGS_DEFAULTS } from '../../constants/device-settings-defaults';
import { EN } from '../../text/en';
import { snackbar } from '../../ui/snackbar-store';
import { PreferenceList } from '../../ui/preferences/PreferenceList';
import { useTheme } from '../../ui/theme';
import { FONT_FAMILY_MONO, FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { SettingsPage } from './settings-ui';
import { useDeviceSettings } from './use-device-settings';

const JSON_INDENT = 2;

export function AdvancedSettingsScreen() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const mode = useAccountMode();
  const [settings, update] = useDeviceSettings();
  const [showDebug, setShowDebug] = useState(false);
  const debug = JSON.stringify(
    {
      app: Constants.expoConfig?.version ?? null,
      platform: Platform.OS,
      os: Platform.Version,
      server: services.config.get()?.serverVersion ?? null,
      api: services.config.get()?.apiVersion ?? null,
      mode,
      settings,
    },
    null,
    JSON_INDENT,
  );
  return (
    <SettingsPage testID="advanced.screen" title={EN['advanced.title']} padded={false}>
      <PreferenceList
        items={[
          {
            kind: 'switch',
            key: 'technical',
            testID: 'settings.technicalDetails',
            icon: 'code',
            title: EN['prefs.technicalDetails'],
            summary: EN['prefs.technicalDetailsSummary'],
            value: settings.showTechnicalDetails,
            onChange: (showTechnicalDetails) => update({ showTechnicalDetails }),
          },
          {
            kind: 'text',
            key: 'rebuild',
            testID: 'advanced.rebuild',
            icon: 'sync',
            title: EN['advanced.rebuild'],
            summary: EN['advanced.rebuildSummary'],
            onPress: () => {
              services.index.clear();
              services.index
                .build()
                .then((report) =>
                  snackbar.show({
                    text: EN['advanced.rebuilt'](report.entries, Math.round(report.durationMs)),
                    testID: 'advanced.message',
                  }),
                )
                .catch(() => undefined);
            },
          },
          {
            kind: 'text',
            key: 'reset',
            testID: 'advanced.reset',
            icon: 'restart',
            title: EN['advanced.reset'],
            summary: EN['advanced.resetSummary'],
            onPress: () => {
              services.deviceSettings
                .update(DEVICE_SETTINGS_DEFAULTS)
                .then(() =>
                  snackbar.show({ text: EN['advanced.resetDone'], testID: 'advanced.message' }),
                )
                .catch(() => undefined);
            },
          },
          {
            kind: 'text',
            key: 'debug',
            testID: 'advanced.debug',
            icon: 'bug',
            title: EN['advanced.debug'],
            summary: EN['advanced.debugSummary'],
            onPress: () => setShowDebug(!showDebug),
          },
        ]}
      />
      {showDebug ? (
        <Text
          testID="advanced.debugText"
          selectable
          style={[styles.debug, { color: colors.text, backgroundColor: colors.surface }]}
        >
          {debug}
        </Text>
      ) : null}
    </SettingsPage>
  );
}

const styles = StyleSheet.create({
  debug: {
    fontFamily: FONT_FAMILY_MONO,
    fontSize: FONT_SIZE.caption,
    margin: SPACING.lg,
    padding: SPACING.md,
    borderRadius: RADIUS.md,
  },
});
