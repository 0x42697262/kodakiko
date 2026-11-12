import { describe, it } from 'vitest';
import { PROVIDER_ENV } from '../integration/test-constants.ts';
import { describeProviderConformance, type ProviderConfig } from './provider-suite.ts';

const REQUIRED = Object.values(PROVIDER_ENV);
const FORCE_PATH_STYLE_TRUE = 'true';

function readConfig(): ProviderConfig | null {
  const values = Object.fromEntries(
    Object.entries(PROVIDER_ENV).map(([name, variable]) => [name, process.env[variable]]),
  ) as Record<keyof typeof PROVIDER_ENV, string | undefined>;
  if (REQUIRED.some((variable) => !process.env[variable])) return null;
  return {
    endpoint: values.endpoint!,
    publicEndpoint: values.publicEndpoint!,
    region: values.region!,
    bucket: values.bucket!,
    accessKeyId: values.accessKeyId!,
    secretAccessKey: values.secretAccessKey!,
    forcePathStyle: values.forcePathStyle === FORCE_PATH_STYLE_TRUE,
    origin: values.origin!,
  };
}

const config = readConfig();

if (config === null) {
  describe('provider conformance', () => {
    it.skip(`skipped: set ${REQUIRED.join(', ')} to run it against a real bucket`, () => undefined);
  });
} else {
  describeProviderConformance(config);
}
