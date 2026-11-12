import { UI_TEST_NEW_PASSWORD, UI_TEST_PASSWORD } from '../../support/constants.ts';
import { seedAccount } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-03', 'android', 0) });
    return {
      USERNAME: account.username,
      WORDS: account.recoveryWords.join(' '),
      OLD_PASSWORD: UI_TEST_PASSWORD,
      NEW_PASSWORD: UI_TEST_NEW_PASSWORD,
    };
  },
  parts: [{ file: 'U-03-recover.yaml' }],
};
