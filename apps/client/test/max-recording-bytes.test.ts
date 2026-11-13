import { describe, expect, it } from 'vitest';
import { STREAM_CHUNK_SIZE_EXPONENT, streamLayout } from '@kodakiko/core';
import { maxPlaintextBytes } from '../src/services/media/max-recording-bytes';

const CIPHERTEXT_LIMITS = [0, 24, 40, 1000, 1_048_600, 1_048_616, 5_000_000, 3_758_096_384];

describe('maxPlaintextBytes', () => {
  it.each(CIPHERTEXT_LIMITS)('keeps the ciphertext within %i bytes and is the largest size that does', (limit) => {
    const plaintext = maxPlaintextBytes(limit);

    if (limit >= 40) {
      expect(streamLayout(plaintext, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize).toBeLessThanOrEqual(limit);
    }
    expect(streamLayout(plaintext + 1, STREAM_CHUNK_SIZE_EXPONENT).ciphertextSize).toBeGreaterThan(limit);
  });
});
