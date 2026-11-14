import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ICON_OUTPUTS,
  ICON_SOURCES,
  ICON_STAMP_FILE,
  maskableSvg,
  sourceHashes,
} from './icons-lib.ts';

const CLIENT_ROOT = fileURLToPath(new URL('..', import.meta.url));
const RENDERER = 'resvg';

const workDir = await mkdtemp(join(tmpdir(), 'kodakiko-icons-'));
try {
  const maskablePath = join(workDir, 'icon-maskable.svg');
  const foreground = await readFile(join(CLIENT_ROOT, ICON_SOURCES.foreground), 'utf8');
  await writeFile(maskablePath, maskableSvg(foreground));

  for (const output of ICON_OUTPUTS) {
    const input =
      output.source === 'maskable' ? maskablePath : join(CLIENT_ROOT, ICON_SOURCES[output.source]);
    for (const path of output.paths) {
      execFileSync(RENDERER, ['-w', String(output.width), input, join(CLIENT_ROOT, path)]);
      console.log(`${path} (${output.width}px)`);
    }
  }

  const stamp = JSON.stringify(await sourceHashes(CLIENT_ROOT), null, 2);
  await writeFile(join(CLIENT_ROOT, ICON_STAMP_FILE), `${stamp}\n`);
  console.log(ICON_STAMP_FILE);
} finally {
  await rm(workDir, { recursive: true, force: true });
}
