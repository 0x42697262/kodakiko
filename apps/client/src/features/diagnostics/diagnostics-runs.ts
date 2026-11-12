import { CRYPTO_VECTORS } from '@kodakiko/core/vectors';
import {
  SALT_BYTES,
  checkVectors,
  deriveWithAdaptiveParams,
  type KdfParams,
  type Sodium,
  type VectorCaseResult,
} from '@kodakiko/core';
import {
  KDF_MEASURE_MEDIAN_INDEX,
  KDF_MEASURE_PASSWORD,
  KDF_MEASURE_RUNS,
} from '../../constants/diagnostics-constants';

export type KdfMeasurement = {
  readonly minMs: number;
  readonly medianMs: number;
  readonly maxMs: number;
  readonly finalParams: Pick<KdfParams, 'opsLimit' | 'memLimitBytes'>;
};

export function runVectors(sodium: Sodium): Promise<readonly VectorCaseResult[]> {
  return checkVectors(sodium, CRYPTO_VECTORS, () => performance.now());
}

export async function measureKdf(sodium: Sodium): Promise<KdfMeasurement> {
  const durations: number[] = [];
  let finalParams: KdfParams | null = null;
  for (let run = 0; run < KDF_MEASURE_RUNS; run += 1) {
    const startedAt = performance.now();
    const result = await deriveWithAdaptiveParams(
      sodium,
      KDF_MEASURE_PASSWORD,
      sodium.randomBytes(SALT_BYTES),
    );
    durations.push(performance.now() - startedAt);
    finalParams = result.params;
  }
  durations.sort((left, right) => left - right);
  return {
    minMs: durations[0] ?? 0,
    medianMs: durations[KDF_MEASURE_MEDIAN_INDEX] ?? 0,
    maxMs: durations[durations.length - 1] ?? 0,
    finalParams: {
      opsLimit: finalParams?.opsLimit ?? 0,
      memLimitBytes: finalParams?.memLimitBytes ?? 0,
    },
  };
}
