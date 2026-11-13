import { Redirect, useLocalSearchParams } from 'expo-router';
import { isValidLocalDate } from '../../src/features/calendar/calendar-model';
import { DayScreen } from '../../src/features/calendar/DayScreen';
import { APP_ROUTES } from '../../src/keys/routes';

export default function DayRoute() {
  const { date } = useLocalSearchParams<{ date?: string }>();
  if (date === undefined || !isValidLocalDate(date)) return <Redirect href={APP_ROUTES.calendar} />;
  return <DayScreen localDate={date} />;
}
