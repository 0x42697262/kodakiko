import { isValidYearMonth, monthGrid, yearMonthOf, type CalendarCell } from '@kodakiko/core';
import { useRouter } from '../../navigation/use-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, Platform, StyleSheet, Text, View, type LayoutChangeEvent } from 'react-native';
import { Touchable } from '../../ui/Touchable';
import { useAppContext } from '../../composition/app-context';
import {
  CALENDAR_CELL_GAP,
  DAYS_PER_WEEK,
  DIMMED_OPACITY,
  DAY_NUMBER_SHADOW_RADIUS,
  NEXT_MONTH,
  PREVIOUS_MONTH,
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
import { IconAction } from '../../ui/IconAction';
import { Screen } from '../../ui/Screen';
import { TextField } from '../../ui/TextField';
import { FONT_SIZE, RADIUS, SPACING } from '../../ui/theme-constants';
import { useTheme } from '../../ui/theme';
import { todayLocalDate, useIndexVersion } from '../gallery/hooks';
import { Sheet } from '../gallery/sheet';
import { Thumbnail } from '../gallery/thumbnail';
import {
  cellLabel,
  formatMonthTitle,
  monthDayInfo,
  weekdayNames,
  type DayInfo,
  canShowMonth,
  monthTarget,
  pagerMonths,
} from './calendar-model';
import { EMPTY_GALLERY_FILTER } from '../gallery/filter-model';
import { useMonthKeys } from './use-month-keys';

const HOME_SCOPE = Object.freeze({ kind: 'home' as const });
const YEAR_MONTH_CHARS = 7;
const MAX_WEEKS = 6;
const NOT_SHOWN = -1;
const PAGER_WINDOW = 3;
const PAGER_SETTLE_MS = 120;
const PAGER_SCROLL_THROTTLE_MS = 32;
const NO_DAYS: ReadonlyMap<string, DayInfo> = new Map();

type CellProps = {
  readonly cell: CalendarCell;
  readonly size: number;
  readonly info: DayInfo | undefined;
  readonly coverMediaId: string | null;
  readonly onOpen: (localDate: string) => void;
};

function MonthPage({
  yearMonth,
  today,
  width,
  cellSize,
  onOpen,
}: {
  readonly yearMonth: string;
  readonly today: string;
  readonly width: number;
  readonly cellSize: number;
  readonly onOpen: (localDate: string) => void;
}) {
  const { services } = useAppContext();
  const version = useIndexVersion();
  const days = useMemo(() => {
    const index = services.index.index();
    return index === null ? NO_DAYS : monthDayInfo(index, yearMonth, HOME_SCOPE);
  }, [services.index, version, yearMonth]);
  const grid = useMemo(() => monthGrid(yearMonth, today, WEEK_STARTS_ON), [yearMonth, today]);
  return (
    <View style={{ width }}>
      {grid.map((week) => (
        <View key={week[0]?.localDate} style={styles.row}>
          {week.map((cell) => {
            const info = cell.inMonth ? days.get(cell.localDate) : undefined;
            const coverMediaId =
              info === undefined
                ? null
                : (services.index.index()?.get(info.coverEntryId)?.coverMediaId ?? null);
            return (
              <DayCell
                key={cell.localDate}
                cell={cell}
                size={cellSize}
                info={info}
                coverMediaId={coverMediaId}
                onOpen={onOpen}
              />
            );
          })}
        </View>
      ))}
    </View>
  );
}

function DayCell({ cell, size, info, coverMediaId, onOpen }: CellProps) {
  const { colors } = useTheme();
  const count = info?.count ?? 0;
  const day = String(
    Number.parseInt(cell.localDate.slice(DATE_DAY_START, DATE_DAY_END), DECIMAL_RADIX),
  );
  const tappable = cell.inMonth && info !== undefined;
  return (
    <Touchable
      testID={cell.inMonth ? calendarDayTestId(cell.localDate) : undefined}
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
        testID={cell.inMonth ? CALENDAR_IDS.dayNumber : undefined}
        style={[
          styles.dayNumber,
          info !== undefined && cell.inMonth
            ? [styles.onCover, { color: colors.onPrimary, textShadowColor: colors.text }]
            : { color: colors.text },
        ]}
      >
        {day}
      </Text>
    </Touchable>
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

  const weekdays = useMemo(() => weekdayNames(WEEK_STARTS_ON), []);
  const currentMonth = today.slice(0, YEAR_MONTH_CHARS);
  const monthsWithEntries = useMemo(() => {
    const index = services.index.index();
    const sections = index?.query({ ...EMPTY_GALLERY_FILTER, scope: HOME_SCOPE }) ?? [];
    return new Set(sections.map((section) => section.localDate.slice(0, YEAR_MONTH_CHARS)));
  }, [services.index, version]);
  const move = useCallback(
    (delta: number) =>
      setYearMonth(
        (current) => monthTarget(current, delta, currentMonth, monthsWithEntries) ?? current,
      ),
    [currentMonth, monthsWithEntries],
  );
  const canGoNext = monthTarget(yearMonth, NEXT_MONTH, currentMonth, monthsWithEntries) !== null;
  const months = useMemo(
    () => pagerMonths(currentMonth, monthsWithEntries, yearMonth),
    [currentMonth, monthsWithEntries, yearMonth],
  );
  const pager = useRef<FlatList<string>>(null);
  const shownIndex = useRef(NOT_SHOWN);
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settle = useCallback(
    (offset: number) => {
      if (width === 0) return;
      const index = Math.round(offset / width);
      const month = months[index];
      if (month === undefined) return;
      shownIndex.current = index;
      setYearMonth(month);
    },
    [months, width],
  );
  const settleSoon = useCallback(
    (offset: number) => {
      if (settleTimer.current !== null) clearTimeout(settleTimer.current);
      settleTimer.current = setTimeout(() => settle(offset), PAGER_SETTLE_MS);
    },
    [settle],
  );
  useEffect(() => {
    const index = months.indexOf(yearMonth);
    if (index < 0 || width === 0 || index === shownIndex.current) return;
    const animated = shownIndex.current >= 0;
    shownIndex.current = index;
    pager.current?.scrollToOffset({ offset: index * width, animated });
  }, [months, yearMonth, width]);
  useMonthKeys(move);

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
    if (!canShowMonth(value, currentMonth, monthsWithEntries)) {
      setJumpError(EN['calendar.jumpEmpty']);
      return;
    }
    setYearMonth(value);
    setJumping(false);
    setJumpError(null);
  }

  return (
    <Screen testID={CALENDAR_IDS.screen}>
      <View style={styles.header}>
        <IconAction
          testID={testId('calendar.previous')}
          label={EN['calendar.previousGlyph']}
          onPress={() => move(PREVIOUS_MONTH)}
          icon="previous"
          tonal={false}
        />
        <Text
          testID={CALENDAR_IDS.monthTitle}
          accessibilityRole="header"
          style={[styles.title, { color: colors.text }]}
        >
          {formatMonthTitle(yearMonth)}
        </Text>
        <IconAction
          testID={testId('calendar.next')}
          label={EN['calendar.nextGlyph']}
          onPress={() => move(NEXT_MONTH)}
          icon="next"
          tonal={false}
          disabled={!canGoNext}
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
      <View onLayout={onLayout}>
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
        {cellSize === 0 ? null : (
          <FlatList
            ref={pager}
            testID="calendar.pager"
            data={months}
            keyExtractor={(month) => month}
            horizontal
            pagingEnabled
            showsHorizontalScrollIndicator={false}
            initialScrollIndex={Math.max(0, months.indexOf(yearMonth))}
            getItemLayout={(_data, index) => ({ length: width, offset: width * index, index })}
            windowSize={PAGER_WINDOW}
            initialNumToRender={1}
            maxToRenderPerBatch={PAGER_WINDOW}
            style={{ height: (cellSize + CALENDAR_CELL_GAP) * MAX_WEEKS }}
            onMomentumScrollEnd={(event) => settle(event.nativeEvent.contentOffset.x)}
            onScroll={
              Platform.OS === 'web'
                ? (event) => settleSoon(event.nativeEvent.contentOffset.x)
                : undefined
            }
            scrollEventThrottle={PAGER_SCROLL_THROTTLE_MS}
            renderItem={({ item }) => (
              <MonthPage
                yearMonth={item}
                today={today}
                width={width}
                cellSize={cellSize}
                onOpen={openDay}
              />
            )}
          />
        )}
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
          <Button
            testID={testId('calendar.jumpSubmit')}
            label={EN['calendar.jumpSubmit']}
            onPress={submitJump}
          />
        </Sheet>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: SPACING.sm,
  },
  title: { fontSize: FONT_SIZE.title, fontWeight: '600', flexShrink: 1, textAlign: 'center' },
  actions: { flexDirection: 'row', gap: SPACING.sm, justifyContent: 'center' },
  row: { flexDirection: 'row', gap: CALENDAR_CELL_GAP, marginBottom: CALENDAR_CELL_GAP },
  weekday: { textAlign: 'center', fontSize: FONT_SIZE.caption },
  cell: { borderRadius: RADIUS.sm, overflow: 'hidden', padding: SPACING.xs },
  dayNumber: { fontSize: FONT_SIZE.caption, fontWeight: '600' },
  onCover: { textShadowRadius: DAY_NUMBER_SHADOW_RADIUS },
});
