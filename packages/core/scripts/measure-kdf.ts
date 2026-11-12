import { performance } from 'node:perf_hooks';
import { deriveWithAdaptiveParams } from '../src/account/index.ts';
import { SALT_BYTES } from '../src/crypto/index.ts';
import { createNodeSodium } from '../test/sodium-node.ts';

const RUNS = 3;
const MEDIAN_INDEX = Math.floor(RUNS / 2);
const MEASURE_PASSWORD = 'measurement password for the kdf baseline';

const sodium = await createNodeSodium();
const durations: number[] = [];
let finalParams = '';
for (let run = 0; run < RUNS; run += 1) {
  const started = performance.now();
  const { params } = await deriveWithAdaptiveParams(
    sodium,
    MEASURE_PASSWORD,
    sodium.randomBytes(SALT_BYTES),
  );
  durations.push(performance.now() - started);
  finalParams = `opsLimit=${params.opsLimit} memLimitBytes=${params.memLimitBytes}`;
}
durations.sort((left, right) => left - right);
const format = (value: number | undefined): string => `${Math.round(value ?? 0)} ms`;
console.log(
  `min ${format(durations[0])}, median ${format(durations[MEDIAN_INDEX])}, max ${format(durations[RUNS - 1])}`,
);
console.log(`final params: ${finalParams}`);
