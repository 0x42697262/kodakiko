import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

const eslint = new ESLint({ cwd: process.cwd() });

async function ruleIds(code: string, filePath: string): Promise<readonly (string | null)[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return result?.messages.map((message) => message.ruleId) ?? [];
}

const PRESSABLE = "import { Pressable } from 'react-native';\nexport const touch = Pressable;\n";
const NO_SCALE = "export const label = <Text allowFontScaling={false}>x</Text>;\n";

describe('client lint rules (spec 24)', () => {
  it('forbids a raw Pressable outside src/ui', async () => {
    expect(await ruleIds(PRESSABLE, 'apps/client/src/features/gallery/zz-lint.tsx')).toContain('no-restricted-imports');
  });

  it('allows Pressable inside src/ui', async () => {
    expect(await ruleIds(PRESSABLE, 'apps/client/src/ui/zz-lint.tsx')).not.toContain('no-restricted-imports');
  });

  it('forbids turning off font scaling', async () => {
    expect(await ruleIds(NO_SCALE, 'apps/client/src/features/gallery/zz-lint.tsx')).toContain('no-restricted-syntax');
  });
});
