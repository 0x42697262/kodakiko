import { calendarDayId, calendarEntries } from '../../support/calendar-entries.ts';
import { seedAccount, seedEntries } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

const SEVENTEENTH = 17;

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-11', 'android', 0) });
    const now = Date.now();
    await seedEntries(account, calendarEntries(now));
    return {
      USERNAME: account.username,
      PASSWORD: account.password,
      DAY_ID: calendarDayId(now, SEVENTEENTH),
    };
  },
  parts: [{ file: 'U-11-calendar.yaml' }],
};
