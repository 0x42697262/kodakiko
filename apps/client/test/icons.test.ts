import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  ICON_BACKGROUND_COLOR,
  ICON_STAMP_FILE,
  maskableSvg,
  sourceHashes,
} from '../scripts/icons-lib.ts';

const CLIENT_ROOT = fileURLToPath(new URL('..', import.meta.url));

describe('app icons', () => {
  it('PNGs were regenerated after the last SVG change (run pnpm icons)', async () => {
    const stamp = JSON.parse(await readFile(join(CLIENT_ROOT, ICON_STAMP_FILE), 'utf8'));

    expect(stamp).toEqual(await sourceHashes(CLIENT_ROOT));
  });

  it('maskable SVG puts the background right after the title', () => {
    const svg = '<svg>\n  <title>K</title>\n  <g/>\n</svg>\n';

    expect(maskableSvg(svg)).toBe(
      `<svg>\n  <title>K</title>\n  <rect width="1024" height="1024" fill="${ICON_BACKGROUND_COLOR}"/>\n  <g/>\n</svg>\n`,
    );
  });

  it('maskable SVG rejects a foreground without a title', () => {
    expect(() => maskableSvg('<svg/>')).toThrow('no <title>');
  });
});
