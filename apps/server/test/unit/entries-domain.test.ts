import { describe, expect, it } from 'vitest';
import {
  canWriteEntry,
  isIdenticalCreate,
  tombstoneCutoff,
  trashCutoff,
  validateCreateMemberships,
  type MembershipTarget,
} from '../../src/domain/entries.ts';

const USER = 'UUUUUUUUUUUUUUUUUUUUUU';
const OTHER = 'OOOOOOOOOOOOOOOOOOOOOO';
const HOME = 'HHHHHHHHHHHHHHHHHHHHHH';
const SHARED = 'SSSSSSSSSSSSSSSSSSSSSS';
const LINK = 'LLLLLLLLLLLLLLLLLLLLLL';
const DAY_MS = 86_400_000;
const NOW = new Date('2026-11-12T10:00:00.000Z');

const home: MembershipTarget = { collectionId: HOME, kind: 'home', ownerId: USER };
const shared: MembershipTarget = { collectionId: SHARED, kind: 'shared', ownerId: USER };
const link: MembershipTarget = { collectionId: LINK, kind: 'link', ownerId: USER };
const FLAGS_OFF = { sharing: false, publicLinks: false };
const FLAGS_ON = { sharing: true, publicLinks: true };

function decide(
  purpose: 'journal' | 'link_copy',
  targets: readonly MembershipTarget[],
  flags = FLAGS_ON,
) {
  return validateCreateMemberships({ userId: USER, purpose, targets, flags });
}

describe('validateCreateMemberships', () => {
  it('accepts a journal entry in the home collection only', () => {
    expect(decide('journal', [home], FLAGS_OFF)).toEqual({ ok: true });
  });

  it('rejects a target owned by someone else with NOT_FOUND', () => {
    expect(decide('journal', [{ ...home, ownerId: OTHER }])).toEqual({
      ok: false,
      code: 'NOT_FOUND',
    });
    expect(decide('journal', [home, { ...shared, ownerId: OTHER }])).toEqual({
      ok: false,
      code: 'NOT_FOUND',
    });
  });

  it('rejects duplicate collection ids with VALIDATION_FAILED', () => {
    expect(decide('journal', [home, home])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
  });

  it('rejects a journal entry without a home membership', () => {
    expect(decide('journal', [shared])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
  });

  it('rejects two home memberships and a link membership on a journal entry', () => {
    const secondHome = { ...home, collectionId: LINK };
    expect(decide('journal', [home, secondHome])).toEqual({
      ok: false,
      code: 'VALIDATION_FAILED',
    });
    expect(decide('journal', [home, link])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
  });

  it('allows a shared membership only with the sharing flag', () => {
    expect(decide('journal', [home, shared], FLAGS_ON)).toEqual({ ok: true });
    expect(decide('journal', [home, shared], FLAGS_OFF)).toEqual({
      ok: false,
      code: 'FEATURE_DISABLED',
    });
    expect(decide('journal', [home, shared], { sharing: false, publicLinks: true })).toEqual({
      ok: false,
      code: 'FEATURE_DISABLED',
    });
  });

  it('accepts a link copy with exactly one link target and the public links flag', () => {
    expect(decide('link_copy', [link], FLAGS_ON)).toEqual({ ok: true });
    expect(decide('link_copy', [link], { sharing: false, publicLinks: true })).toEqual({
      ok: true,
    });
  });

  it('rejects a link copy with the flag off', () => {
    expect(decide('link_copy', [link], FLAGS_OFF)).toEqual({
      ok: false,
      code: 'FEATURE_DISABLED',
    });
    expect(decide('link_copy', [link], { sharing: true, publicLinks: false })).toEqual({
      ok: false,
      code: 'FEATURE_DISABLED',
    });
  });

  it('rejects a link copy with a wrong kind or count', () => {
    expect(decide('link_copy', [home])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
    expect(decide('link_copy', [shared])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
    expect(decide('link_copy', [link, home])).toEqual({ ok: false, code: 'VALIDATION_FAILED' });
  });

  it('checks ownership before duplicates and kinds', () => {
    expect(decide('journal', [{ ...home, ownerId: OTHER }, home])).toEqual({
      ok: false,
      code: 'NOT_FOUND',
    });
  });
});

describe('canWriteEntry', () => {
  it('allows only the owner', () => {
    expect(canWriteEntry(USER, USER)).toBe(true);
    expect(canWriteEntry(OTHER, USER)).toBe(false);
  });
});

describe('isIdenticalCreate', () => {
  const existing = {
    ownerId: USER,
    purpose: 'journal' as const,
    metadataBlob: Buffer.from([1, 2, 3]),
    collectionIds: [HOME, SHARED],
    mediaIds: ['m1', 'm2'],
  };
  const request = {
    ownerId: USER,
    purpose: 'journal' as const,
    metadataBlob: Buffer.from([1, 2, 3]),
    collectionIds: [SHARED, HOME],
    mediaIds: ['m2', 'm1'],
  };

  it('treats the same content as identical regardless of order', () => {
    expect(isIdenticalCreate(existing, request)).toBe(true);
  });

  it('detects a different owner, purpose, blob, collections or media', () => {
    expect(isIdenticalCreate(existing, { ...request, ownerId: OTHER })).toBe(false);
    expect(isIdenticalCreate(existing, { ...request, purpose: 'link_copy' })).toBe(false);
    expect(isIdenticalCreate(existing, { ...request, metadataBlob: Buffer.from([1, 2, 4]) })).toBe(
      false,
    );
    expect(isIdenticalCreate(existing, { ...request, collectionIds: [HOME] })).toBe(false);
    expect(isIdenticalCreate(existing, { ...request, collectionIds: [HOME, LINK] })).toBe(false);
    expect(isIdenticalCreate(existing, { ...request, mediaIds: ['m1'] })).toBe(false);
    expect(isIdenticalCreate(existing, { ...request, mediaIds: ['m1', 'm3'] })).toBe(false);
  });

  it('is not fooled by a duplicate id that equals the other set in size', () => {
    expect(isIdenticalCreate(existing, { ...request, mediaIds: ['m1', 'm1'] })).toBe(false);
  });
});

describe('cutoffs', () => {
  it('subtracts whole days', () => {
    expect(trashCutoff(NOW, 30).getTime()).toBe(NOW.getTime() - 30 * DAY_MS);
    expect(tombstoneCutoff(NOW, 90).getTime()).toBe(NOW.getTime() - 90 * DAY_MS);
  });
});
