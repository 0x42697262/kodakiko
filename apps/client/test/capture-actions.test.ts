import { describe, expect, it } from 'vitest';
import { captureActionsEnabled } from '../src/features/capture/capture-actions';

describe('captureActionsEnabled (v0.2.1 fix B3)', () => {
  it('enables Next with at least one item and nothing recording', () => {
    expect(captureActionsEnabled({ items: 1, pending: 0, recording: false }).next).toBe(true);
    expect(captureActionsEnabled({ items: 0, pending: 0, recording: false }).next).toBe(false);
  });

  it('disables Next and Add to entry while a video is recording', () => {
    expect(captureActionsEnabled({ items: 2, pending: 0, recording: true })).toEqual({ next: false, addToEntry: false });
  });

  it('keeps Add to entry disabled while imports are pending', () => {
    expect(captureActionsEnabled({ items: 2, pending: 1, recording: false }).addToEntry).toBe(false);
  });
});
