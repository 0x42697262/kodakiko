import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

export const ICON_BACKGROUND_COLOR = '#1e1d2b';
export const ICON_STAMP_FILE = join('assets', 'icons.sha256.json');

export const ICON_SOURCES = {
  full: join('assets', 'icon.svg'),
  foreground: join('assets', 'icon-foreground.svg'),
} as const;

export type IconSource = keyof typeof ICON_SOURCES | 'maskable';

export type IconOutput = {
  readonly source: IconSource;
  readonly width: number;
  readonly paths: readonly string[];
};

export const ICON_OUTPUTS: readonly IconOutput[] = [
  { source: 'full', width: 1024, paths: [join('assets', 'icon.png')] },
  { source: 'foreground', width: 1024, paths: [join('assets', 'adaptive-icon.png')] },
  {
    source: 'full',
    width: 192,
    paths: [join('assets', 'icon-192.png'), join('public', 'icon-192.png')],
  },
  {
    source: 'full',
    width: 512,
    paths: [join('assets', 'icon-512.png'), join('public', 'icon-512.png')],
  },
  { source: 'maskable', width: 512, paths: [join('public', 'icon-maskable-512.png')] },
];

const SVG_TITLE_END = '</title>\n';

export function maskableSvg(foregroundSvg: string): string {
  const background = `  <rect width="1024" height="1024" fill="${ICON_BACKGROUND_COLOR}"/>\n`;
  const at = foregroundSvg.indexOf(SVG_TITLE_END);
  if (at < 0) throw new Error('icon-foreground.svg has no <title>');
  const end = at + SVG_TITLE_END.length;
  return foregroundSvg.slice(0, end) + background + foregroundSvg.slice(end);
}

export async function sourceHashes(clientRoot: string): Promise<Record<string, string>> {
  const entries = await Promise.all(
    Object.values(ICON_SOURCES).map(async (path) => {
      const bytes = await readFile(join(clientRoot, path));
      return [path, createHash('sha256').update(bytes).digest('hex')] as const;
    }),
  );
  return Object.fromEntries(entries);
}
