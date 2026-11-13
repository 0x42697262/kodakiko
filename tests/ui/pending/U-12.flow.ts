import { seedAccount, seedEntries } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-12', 'android', 0) });
    await seedEntries(account, [
      {
        takenAtUtc: '2026-05-01T08:00:00.000Z',
        offsetMinutes: 0,
        note: '',
        tags: [],
        media: ['photo-gps.jpg', 'photo-portrait.jpg'],
      },
    ]);
    return { USERNAME: account.username, PASSWORD: account.password };
  },
  parts: [{ file: 'U-12-zoom.yaml' }],
};
