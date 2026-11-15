import { registerHooks } from 'node:module';

const RELATIVE_PREFIX = '.';
const SOURCE_SUFFIXES = ['.ts', '.tsx', '/index.ts'] as const;

registerHooks({
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      if (!specifier.startsWith(RELATIVE_PREFIX)) throw error;
      for (const suffix of SOURCE_SUFFIXES) {
        try {
          return nextResolve(`${specifier}${suffix}`, context);
        } catch {
          continue;
        }
      }
      throw error;
    }
  },
});
