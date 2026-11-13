import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: { include: ['infra/scripts/test/**/*.test.ts', 'scripts/test/**/*.test.ts'] },
});
