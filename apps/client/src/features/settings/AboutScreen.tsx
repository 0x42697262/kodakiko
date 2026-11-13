import Constants from 'expo-constants';
import { Text } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Body } from '../../ui/Typography';
import { useTheme } from '../../ui/theme';
import { LabeledValue, SettingsPage } from './settings-ui';
import licenses from '../../../assets/licenses.json';

type License = { readonly name: string; readonly version: string; readonly license: string };

const UNKNOWN = '\u2014';

export function AboutScreen() {
  const { services } = useAppContext();
  const { colors } = useTheme();
  const list = [...(licenses as readonly License[])].sort((left, right) => left.name.localeCompare(right.name));
  return (
    <SettingsPage testID="settings.aboutScreen" title={EN['settings.rowAbout']}>
      <LabeledValue testID="settings.appVersion" label={EN['settings.appVersion']} value={Constants.expoConfig?.version ?? UNKNOWN} />
      <LabeledValue testID="settings.serverVersion" label={EN['settings.serverVersion']} value={services.config.get()?.serverVersion ?? UNKNOWN} />
      <Text style={{ color: colors.text }}>{EN['settings.licenses']}</Text>
      {list.length === 0 ? <Body testID="settings.licensesEmpty" text={EN['settings.licensesEmpty']} /> : null}
      {list.map((item) => (
        <Text key={`${item.name}@${item.version}`} style={{ color: colors.textMuted }}>
          {item.name} {item.version} · {item.license}
        </Text>
      ))}
    </SettingsPage>
  );
}
