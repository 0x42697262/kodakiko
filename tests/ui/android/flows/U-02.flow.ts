import { UI_TEST_PASSWORD, WRONG_TEST_PASSWORD } from '../../support/constants.ts';
import { seedAccount } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const account = await seedAccount({ username: uiUsername('U-02', 'android', 0) });
    return {
      USERNAME: account.username,
      PASSWORD: account.password,
      WRONG_PASSWORD: WRONG_TEST_PASSWORD,
      UNKNOWN_USERNAME: uiUsername('U-02', 'android', 1),
      OTHER_PASSWORD: UI_TEST_PASSWORD,
    };
  },
  parts: [{ file: 'U-02-sign-in.yaml' }],
};
