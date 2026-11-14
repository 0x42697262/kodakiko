import { StyleSheet, Text, View } from 'react-native';
import { openMap } from '../../adapters/maps/open-map';
import { COORDINATE_DECIMALS } from '../../constants/entry-constants';
import { ENTRY_IDS } from '../../keys/entry-ids';
import { testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { IconAction } from '../../ui/IconAction';
import { FONT_SIZE, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';

export type LocationRowProps = {
  readonly showOpen?: boolean;
  readonly latitude: number;
  readonly longitude: number;
  readonly source: 'exif' | 'device';
  readonly onRemove?: () => void;
};

export function LocationRow({
  latitude,
  longitude,
  source,
  onRemove,
  showOpen = true,
}: LocationRowProps) {
  const { colors } = useTheme();
  const coordinates = `${latitude.toFixed(COORDINATE_DECIMALS)}, ${longitude.toFixed(COORDINATE_DECIMALS)}`;
  return (
    <View style={styles.container}>
      <Text
        testID={ENTRY_IDS.locationCoordinates}
        style={[styles.coordinates, { color: colors.text }]}
      >
        {coordinates}
      </Text>
      <Text testID={ENTRY_IDS.locationSource} style={[styles.source, { color: colors.textMuted }]}>
        {source === 'exif' ? EN['location.sourceExif'] : EN['location.sourceDevice']}
      </Text>
      <View style={styles.row}>
        {showOpen ? (
          <IconAction
            testID={testId('location.open')}
            icon="map"
            label={EN['location.open']}
            onPress={() => {
              openMap(latitude, longitude).catch(() => undefined);
            }}
          />
        ) : null}
        {onRemove === undefined ? null : (
          <IconAction
            testID={testId('location.remove')}
            icon="locationOff"
            label={EN['location.remove']}
            onPress={onRemove}
          />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: SPACING.xs },
  coordinates: { fontSize: FONT_SIZE.body },
  source: { fontSize: FONT_SIZE.caption },
  row: { flexDirection: 'row', gap: SPACING.md },
});
