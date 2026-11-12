import type { ErrorCode } from '@kodakiko/contract';
import { DAY_MS } from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import type { CollectionKind, EntryPurpose } from './enums.ts';

export type Flags = Pick<Settings['flags'], 'sharing' | 'publicLinks'>;

export type MembershipTarget = {
  readonly collectionId: string;
  readonly kind: CollectionKind;
  readonly ownerId: string;
};

export type MembershipDecision =
  | { readonly ok: true }
  | { readonly ok: false; readonly code: ErrorCode };

export type ExistingEntrySnapshot = {
  readonly ownerId: string;
  readonly purpose: EntryPurpose;
  readonly metadataBlob: Uint8Array;
  readonly collectionIds: readonly string[];
  readonly mediaIds: readonly string[];
};

export type CreateSnapshot = ExistingEntrySnapshot;

const REFUSE_VALIDATION: MembershipDecision = { ok: false, code: 'VALIDATION_FAILED' };
const REFUSE_DISABLED: MembershipDecision = { ok: false, code: 'FEATURE_DISABLED' };
const ACCEPT: MembershipDecision = { ok: true };

function hasDuplicates(values: readonly string[]): boolean {
  return new Set(values).size !== values.length;
}

function validateJournal(targets: readonly MembershipTarget[], flags: Flags): MembershipDecision {
  const homes = targets.filter((target) => target.kind === 'home');
  if (homes.length !== 1) return REFUSE_VALIDATION;
  if (targets.some((target) => target.kind === 'link')) return REFUSE_VALIDATION;
  const hasShared = targets.some((target) => target.kind === 'shared');
  if (hasShared && !flags.sharing) return REFUSE_DISABLED;
  return ACCEPT;
}

function validateLinkCopy(targets: readonly MembershipTarget[], flags: Flags): MembershipDecision {
  const [only, ...rest] = targets;
  if (only === undefined || rest.length > 0 || only.kind !== 'link') return REFUSE_VALIDATION;
  if (!flags.publicLinks) return REFUSE_DISABLED;
  return ACCEPT;
}

const VALIDATORS: Readonly<
  Record<EntryPurpose, (targets: readonly MembershipTarget[], flags: Flags) => MembershipDecision>
> = Object.freeze({ journal: validateJournal, link_copy: validateLinkCopy });

export function validateCreateMemberships(input: {
  readonly userId: string;
  readonly purpose: EntryPurpose;
  readonly targets: readonly MembershipTarget[];
  readonly flags: Flags;
}): MembershipDecision {
  const { userId, purpose, targets, flags } = input;
  if (targets.some((target) => target.ownerId !== userId)) return { ok: false, code: 'NOT_FOUND' };
  if (hasDuplicates(targets.map((target) => target.collectionId))) return REFUSE_VALIDATION;
  return VALIDATORS[purpose](targets, flags);
}

export function canWriteEntry(userId: string, entryOwnerId: string): boolean {
  return userId === entryOwnerId;
}

function sameSet(left: readonly string[], right: readonly string[]): boolean {
  const leftSet = new Set(left);
  const rightSet = new Set(right);
  if (leftSet.size !== left.length || rightSet.size !== right.length) return false;
  return leftSet.size === rightSet.size && [...leftSet].every((value) => rightSet.has(value));
}

export function isIdenticalCreate(
  existing: ExistingEntrySnapshot,
  request: CreateSnapshot,
): boolean {
  return (
    existing.ownerId === request.ownerId &&
    existing.purpose === request.purpose &&
    Buffer.from(existing.metadataBlob).equals(Buffer.from(request.metadataBlob)) &&
    sameSet(existing.collectionIds, request.collectionIds) &&
    sameSet(existing.mediaIds, request.mediaIds)
  );
}

export function trashCutoff(now: Date, trashDays: number): Date {
  return new Date(now.getTime() - trashDays * DAY_MS);
}

export function tombstoneCutoff(now: Date, retentionDays: number): Date {
  return new Date(now.getTime() - retentionDays * DAY_MS);
}
