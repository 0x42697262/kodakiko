import { StyleSheet, View } from 'react-native';
import { EN } from '../../text/en';
import { useTheme } from '../../ui/theme';
import { Touchable } from '../../ui/Touchable';

const DOT = 6;
const ACTIVE_DOT_WIDTH = 16;
const DOT_GAP = 6;
const HIT_SLOP = 8;
const MIN_PAGES = 2;

export function PageDots({
  count,
  active,
  onSelect,
}: {
  readonly count: number;
  readonly active: number;
  readonly onSelect: (index: number) => void;
}) {
  const { colors } = useTheme();
  if (count < MIN_PAGES) return null;
  return (
    <View testID="edit.dots" style={styles.row}>
      {Array.from({ length: count }, (_unused, index) => (
        <Touchable
          key={index}
          testID={`edit.dot.${index}`}
          accessibilityRole="button"
          accessibilityLabel={EN['edit.page'](index + 1, count)}
          accessibilityState={{ selected: index === active }}
          hitSlop={HIT_SLOP}
          borderless
          onPress={() => onSelect(index)}
          style={[
            styles.dot,
            {
              backgroundColor: index === active ? colors.primary : colors.border,
              width: index === active ? ACTIVE_DOT_WIDTH : DOT,
            },
          ]}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'center', alignItems: 'center', gap: DOT_GAP },
  dot: { height: DOT, borderRadius: DOT },
});
