import { useRouter } from 'expo-router';
import { StyleSheet, Text } from 'react-native';
import { useAppContext } from '../../composition/app-context';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { Body, Title } from '../../ui/Typography';
import { FONT_FAMILY_MONO, FONT_SIZE } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { SHARING_IDS } from '../sharing/sharing-ids';

export function AccountSettingsScreen() {
  const router = useRouter();
  const { services } = useAppContext();
  const { colors } = useTheme();
  return (
    <Screen testID="settings.accountScreen" scroll>
      <Button testID="shared.back" label={EN['shared.back']} variant="secondary" onPress={() => router.back()} />
      <Title text={EN['settings.accountTitle']} />
      <Text style={[styles.label, { color: colors.textMuted }]}>{EN['settings.securityCode']}</Text>
      <Text testID={SHARING_IDS.securityCodeOwn} selectable style={[styles.code, { color: colors.text }]}>
        {services.sharing.myFingerprint()}
      </Text>
      <Body text={EN['settings.securityCodeHelp']} />
    </Screen>
  );
}

const styles = StyleSheet.create({
  label: { fontSize: FONT_SIZE.caption },
  code: { fontFamily: FONT_FAMILY_MONO, fontSize: FONT_SIZE.title },
});
