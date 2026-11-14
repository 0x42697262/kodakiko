import { ENTRY_TIME_LOCALE, ENTRY_TIME_ZONE, MS_PER_DAY } from '../../constants/entry-constants';
import { DAY_HEADER_YEAR_LENGTH } from '../../constants/gallery-constants';
import { EN } from '../../text/en';

const MIDNIGHT_SUFFIX = 'T00:00:00.000Z';
const ISO_DATE_LENGTH = 10;

const WEEKDAY_FORMAT = new Intl.DateTimeFormat(ENTRY_TIME_LOCALE, {
  weekday: 'long',
  month: 'short',
  day: 'numeric',
  timeZone: ENTRY_TIME_ZONE,
});

const FULL_FORMAT = new Intl.DateTimeFormat(ENTRY_TIME_LOCALE, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  timeZone: ENTRY_TIME_ZONE,
});

function midnight(localDate: string): Date {
  return new Date(`${localDate}${MIDNIGHT_SUFFIX}`);
}

export function formatDayHeader(localDate: string, today: string): string {
  if (localDate === today) return EN['gallery.today'];
  const yesterday = new Date(midnight(today).getTime() - MS_PER_DAY)
    .toISOString()
    .slice(0, ISO_DATE_LENGTH);
  if (localDate === yesterday) return EN['gallery.yesterday'];
  const sameYear =
    localDate.slice(0, DAY_HEADER_YEAR_LENGTH) === today.slice(0, DAY_HEADER_YEAR_LENGTH);
  return (sameYear ? WEEKDAY_FORMAT : FULL_FORMAT).format(midnight(localDate));
}

const MONTH_FORMAT = new Intl.DateTimeFormat(ENTRY_TIME_LOCALE, { year: 'numeric', month: 'long', timeZone: ENTRY_TIME_ZONE });
const MONTH_KEY = /^m:(\d{4}-\d{2})$/;
const YEAR_KEY = /^y:(\d{4})$/;
const TAG_KEY = /^t:(.+)$/;
const UNTAGGED_KEY = 'n:';
const FIRST_OF_MONTH = '-01';

export function formatGroupHeader(key: string, today: string): string {
  const month = MONTH_KEY.exec(key);
  if (month !== null) return MONTH_FORMAT.format(midnight(`${month[1] ?? ''}${FIRST_OF_MONTH}`));
  const year = YEAR_KEY.exec(key);
  if (year !== null) return year[1] ?? key;
  const tag = TAG_KEY.exec(key);
  if (tag !== null) return `#${tag[1] ?? ''}`;
  if (key === UNTAGGED_KEY) return EN['gallery.untagged'];
  return formatDayHeader(key, today);
}
