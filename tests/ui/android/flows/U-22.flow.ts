import { UI_TEST_PASSWORD } from '../../support/constants.ts';
import { uiUsername } from '../../support/usernames.ts';

export default {
  seed: async () => ({ USERNAME: uiUsername('U-22', 'android', 0), PASSWORD: UI_TEST_PASSWORD }),
  parts: [{ file: 'U-22-local-setup.yaml' }],
};
