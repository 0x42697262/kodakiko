import { describe, expect, it } from 'vitest';
import { createRandomId } from '../src/adapters/ids';
import { systemClock } from '../src/adapters/clock';
import { runIndexBenchmark } from '../src/features/diagnostics/index-benchmark';
import { createNodeSodium } from './support';

describe('index benchmark', () => {
  it('seals synthetic entries in memory, decrypts them through the index path and reports timings', async () => {
    const sodium = await createNodeSodium();
    const result = await runIndexBenchmark({
      sodium,
      randomId: createRandomId(sodium),
      clock: systemClock,
      count: 50,
    });
    expect(result.entries).toBe(50);
    expect(result.unreadable).toBe(0);
    expect(result.buildMs).toBeGreaterThanOrEqual(0);
    expect(result.queryMs).toBeGreaterThanOrEqual(0);
    expect(result.entriesPerSecond).toBeGreaterThan(0);
    expect(result.withinBudget).toBe(true);
  });
});
