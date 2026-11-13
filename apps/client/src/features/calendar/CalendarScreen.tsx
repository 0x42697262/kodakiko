import { addMonths, isValidYearMonth, monthGrid, yearMonthOf, type CalendarCell } from '@kodakiko/core';
import { useRouter } from 'expo-router';
import { useCallback, useMemo, useState } from 'react';
import {
  PanResponder,
  Pressable,
  StyleSheet,
  Text,
  View,
  type LayoutChangeEvent,
} from 'react-native';
import { useAppContext } from '../../composition/app-context';
import {
  CALENDAR_CELL_GAP,
  DAYS_PER_WEEK,
  DIMMED_OPACITY,
  DAY_NUMBER_SHADOW_RADIUS,
  NEXT_MONTH,
  PREVIOUS_MONTH,
  SWIPE_CLAIM_DISTANCE,
  SWIPE_DIRECTION_RATIO,
  SWIPE_MIN_DISTANCE,
  TODAY_RING_WIDTH,
  WEEK_STARTS_ON,
  DATE_DAY_START,
  DATE_DAY_END,
  DECIMAL_RADIX,
} from '../../constants/calendar-constants';
import { CALENDAR_IDS, calendarDayTestId } from '../../keys/calendar-ids';
import { dayRoute } from '../../keys/routes';
import { listTestId, testId } from '../../keys/test-ids';
import { EN } from '../../text/en';
import { Button } from '../../ui/Button';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { todayLocalDate, useIndexVersion } from '../gallery/hooks';
import { Sheet } from '../gallery/sheet';
import { Thumbnail } from '../gallery/thumbnail';
import { cellLabel, formatMonthTitle, monthDayInfo, weekdayNames, type DayInfo } from './calendar-model';
import { useMonthKeys } from './use-month-keys';

const HOME_SCOPE = Object.freeze({ kind: 'home' as const });
const NO_DAYS: ReadonlyMap<string, DayInfo> = new Map();

type CellProps = {
  readonly cell: CalendarCell;
  readonly size: number;
  readonly info: DayInfo | undefined;
  readonly coverMediaId: string | null;
  readonly onOpen: (localDate: string) => void;
};

function DayCell({ cell, size, info, coverMediaId, onOpen }: CellProps) {
  const { colors } = useTheme();
  const count = info?.count ?? 0;
  const day = String(Number.parseInt(cell.localDate.slice(DATE_DAY_START, DATE_DAY_END), DECIMAL_RADIX));
  const tappable = cell.inMonth && info !== undefined;
  return (
    <Pressable
      testID={calendarDayTestId(cell.localDate)}
      accessibilityRole="button"
      accessibilityLabel={cellLabel(cell.localDate, count)}
      accessibilityState={{ disabled: !tappable }}
      disabled={!tappable}
      onPress={() => onOpen(cell.localDate)}
      style={[
        styles.cell,
        { width: size, height: size, backgroundColor: colors.surface },
        cell.inMonth ? null : { opacity: DIMMED_OPACITY },
        cell.isToday ? { borderWidth: TODAY_RING_WIDTH, borderColor: colors.focus } : null,
      ]}
    >
      {info !== undefined && cell.inMonth ? (
        <View style={StyleSheet.absoluteFill}>
          <Thumbnail entryId={info.coverEntryId} mediaId={coverMediaId} size={size} />
        </View>
      ) : null}
      <Text
        testID={CALENDAR_IDS.dayNumber}
        style={[
          styles.dayNumber,
          info !== undefined && cell.inMonth
            ? [styles.onCover, { color: colors.onPrimary, textShadowColor: colors.text }]
            : { color: colors.text },
        ]}
      >
        {day}
      </Text>
    </Pressable>
  );
}

export function CalendarScreen() {
  const router = useRouter();
  const { services, clock } = useAppContext();
  const { colors } = useTheme();
  const today = todayLocalDate(clock.now());
  const version = useIndexVersion();
  const [yearMonth, setYearMonth] = useState(() => yearMonthOf(today));
  const [width, setWidth] = useState(0);
  const [jumping, setJumping] = useState(false);
  const [jumpText, setJumpText] = useState('');
  const [jumpError, setJumpError] = useState<string | null>(null);

  const days = useMemo(() => {
    const index = services.index.index();
    return index === null ? NO_DAYS : monthDayInfo(index, yearMonth, HOME_SCOPE);
  }, [services.index, version, yearMonth]);
  const grid = useMemo(() => monthGrid(yearMonth, today, WEEK_STARTS_ON), [yearMonth, today]);
  const weekdays = useMemo(() => weekdayNames(WEEK_STARTS_ON), []);
  const move = useCallback((delta: number) => setYearMonth((current) => addMonths(current, delta)), []);
  useMonthKeys(move);

  const swipe = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_event, gesture) =>
          Math.abs(gesture.dx) > SWIPE_CLAIM_DISTANCE &&
          Math.abs(gesture.dx) > Math.abs(gesture.dy) * SWIPE_DIRECTION_RATIO,
        onPanResponderRelease: (_event, gesture) => {
          if (gesture.dx >= SWIPE_MIN_DISTANCE) move(PREVIOUS_MONTH);
          else if (gesture.dx <= -SWIPE_MIN_DISTANCE) move(NEXT_MONTH);
        },
      }),
    [move],
  );

  const cellSize =
    width === 0 ? 0 : Math.floor((width - CALENDAR_CELL_GAP * (DAYS_PER_WEEK - 1)) / DAYS_PER_WEEK);

  function onLayout(event: LayoutChangeEvent): void {
    setWidth(event.nativeEvent.layout.width);
  }

  function openDay(localDate: string): void {
    router.push(dayRoute(localDate));
  }

  function submitJump(): void {
    const value = jumpText.trim();
    if (!isValidYearMonth(value)) {
      setJumpError(EN['calendar.jumpInvalid']);
      return;
    }
    setYearMonth(value);
    setJumping(false);
    setJumpError(null);
  }

  return (
    <Screen testID={CALENDAR_IDS.screen}>
      <View style={styles.header}>
        <Button
          testID={testId('calendar.previous')}
          label={EN['calendar.previousGlyph']}
          variant="secondary"
          onPress={() => move(PREVIOUS_MONTH)}
        />
        <Text
          testID={CALENDAR_IDS.monthTitle}
          accessibilityRole="header"
          style={[styles.title, { color: colors.text }]}
        >
          {formatMonthTitle(yearMonth)}
        </Text>
        <Button
          testID={testId('calendar.next')}
          label={EN['calendar.nextGlyph']}
          variant="secondary"
          onPress={() => move(NEXT_MONTH)}
        />
      </View>
      <View style={styles.actions}>
        <Button
          testID={testId('calendar.today')}
          label={EN['calendar.today']}
          variant="secondary"
          onPress={() => setYearMonth(yearMonthOf(today))}
        />
        <Button
          testID={testId('calendar.jump')}
          label={EN['calendar.jump']}
          variant="secondary"
          onPress={() => {
            setJumpText(yearMonth);
            setJumpError(null);
            setJumping(true);
          }}
        />
      </View>
      <View onLayout={onLayout} {...swipe.panHandlers}>
        <View style={styles.row}>
          {weekdays.map((name, position) => (
            <Text
              key={name}
              testID={listTestId(CALENDAR_IDS.weekday, position)}
              style={[styles.weekday, { width: cellSize, color: colors.textMuted }]}
            >
              {name}
            </Text>
          ))}
        </View>
        {grid.map((week) => (
          <View key={week[0]?.localDate} style={styles.row}>
            {week.map((cell) => {
              const info = cell.inMonth ? days.get(cell.localDate) : undefined;
              const coverMediaId =
                info === undefined ? null : (services.index.index()?.get(info.coverEntryId)?.coverMediaId ?? null);
              return (
                <DayCell
                  key={cell.localDate}
                  cell={cell}
                  size={cellSize}
                  info={info}
                  coverMediaId={coverMediaId}
                  onOpen={openDay}
                />
              );
            })}
          </View>
        ))}
      </View>
      {jumping ? (
        <Sheet testID={CALENDAR_IDS.jumpSheet} onClose={() => setJumping(false)}>
          <TextField
            testID={testId('calendar.jumpField')}
            label={EN['calendar.jumpField']}
            value={jumpText}
            onChangeText={setJumpText}
            onSubmit={submitJump}
            errorText={jumpError}
            errorTestID={CALENDAR_IDS.jumpError}
            selectOnFocus
          />
          <Button testID={testId('calendar.jumpSubmit')} label={EN['calendar.jumpSubmit']} onPress={submitJump} />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACING.sm },
  title: { fontSize: FONT_SIZE.title, fontWeight: '600', flexShrink: 1, textAlign: 'center' },
  actions: { flexDirection: 'row', gap: SPACING.sm, justifyContent: 'center' },
  row: { flexDirection: 'row', gap: CALENDAR_CELL_GAP, marginBottom: CALENDAR_CELL_GAP },
  weekday: { textAlign: 'center', fontSize: FONT_SIZE.caption },
  cell: { borderRadius: RADIUS.sm, overflow: 'hidden', padding: SPACING.xs },
  dayNumber: { fontSize: FONT_SIZE.caption, fontWeight: '600' },
  onCover: { textShadowRadius: DAY_NUMBER_SHADOW_RADIUS },
});
