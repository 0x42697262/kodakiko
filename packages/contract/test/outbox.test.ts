import { describe, expect, it } from 'vitest';
import { OUTBOX_KINDS, entryOutboxPayloadSchemas } from '../src/index.ts';

const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_B = 'BBBBBBBBBBBBBBBBBBBBBB';

describe('outbox kinds', () => {
  it('lists the seven kinds in the planned order', () => {
    expect(OUTBOX_KINDS).toEqual([
      'createEntry',
      'updateEntryMeta',
      'addMedia',
      'removeMedia',
      'trashEntry',
      'restoreEntry',
      'deleteEntry',
    ]);
  });

  it('has one payload schema per kind', () => {
    expect(Object.keys(entryOutboxPayloadSchemas).sort()).toEqual([...OUTBOX_KINDS].sort());
  });
});

describe('outbox payload schemas', () => {
  it('accepts entry ids for entry-level kinds', () => {
    for (const kind of ['createEntry', 'updateEntryMeta', 'trashEntry', 'restoreEntry', 'deleteEntry'] as const) {
      expect(entryOutboxPayloadSchemas[kind].safeParse({ entryId: ID_A }).success).toBe(true);
    }
  });

  it('requires entry and media ids for media kinds', () => {
    for (const kind of ['addMedia', 'removeMedia'] as const) {
      expect(entryOutboxPayloadSchemas[kind].safeParse({ entryId: ID_A, mediaId: ID_B }).success).toBe(true);
      expect(entryOutboxPayloadSchemas[kind].safeParse({ entryId: ID_A }).success).toBe(false);
    }
  });

  it('rejects malformed ids', () => {
    expect(entryOutboxPayloadSchemas.createEntry.safeParse({ entryId: 'short' }).success).toBe(false);
  });
});
