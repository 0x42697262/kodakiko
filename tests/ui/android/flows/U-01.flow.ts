import { UI_TEST_PASSWORD } from '../../support/constants.ts';
import { seedInvite } from '../../support/seed.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => {
    const invite = await seedInvite('member');
    return {
      SERVER_INPUT: invite.url,
      USERNAME: uiUsername('U-01', 'android', 0),
      PASSWORD: UI_TEST_PASSWORD,
    };
  },
  parts: [{ file: 'U-01-sign-up.yaml' }],
};
