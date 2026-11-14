import { readFile, writeFile } from 'node:fs/promises';
import { createInterface } from 'node:readline/promises';
import { decryptKdkx, deriveKdkxKey, KDKX_EXTENSION, readKdkxParams } from '../src/index.ts';
import { createNodeSodium } from '../test/sodium-node.ts';

const PASSPHRASE_ENV = 'KODAKIKO_EXPORT_PASSPHRASE';
const USAGE = 'usage: pnpm --filter @kodakiko/core decrypt-export <part.zip.kdkx> [more parts...]\n';

async function passphrase(): Promise<string> {
  const fromEnv = process.env[PASSPHRASE_ENV];
  if (fromEnv !== undefined && fromEnv !== '') return fromEnv;
  const prompt = createInterface({ input: process.stdin, output: process.stderr });
  const answer = await prompt.question('Export passphrase: ');
  prompt.close();
  return answer;
}

const files = process.argv.slice(2);
if (files.length === 0) {
  process.stderr.write(USAGE);
  process.exit(1);
}
const sodium = await createNodeSodium();
const secret = await passphrase();
for (const file of files) {
  const bytes = new Uint8Array(await readFile(file));
  const key = await deriveKdkxKey(sodium, secret, readKdkxParams(bytes));
  const plain = decryptKdkx(sodium, key, bytes);
  const out = file.endsWith(KDKX_EXTENSION) ? file.slice(0, -KDKX_EXTENSION.length) : `${file}.zip`;
  await writeFile(out, plain);
  process.stdout.write(`${file} -> ${out}\n`);
}
