import type { UserRole } from './enums.ts';

const IEC_STEP = 1024;
const IEC_UNITS = Object.freeze(['B', 'KiB', 'MiB', 'GiB', 'TiB']);
const ADMIN_ROLE: UserRole = 'admin';

export type AdminRefusal = 'selfDemotion' | 'lastAdmin';

export function formatBytesIec(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= IEC_STEP && unit < IEC_UNITS.length - 1) {
    value /= IEC_STEP;
    unit += 1;
  }
  const label = IEC_UNITS[unit] ?? '';
  return unit === 0 ? `${value} ${label}` : `${value.toFixed(1)} ${label}`;
}

export function roleChangeRefusal(input: {
  readonly actorId: string | null;
  readonly targetId: string;
  readonly currentRole: UserRole;
  readonly newRole: UserRole;
  readonly adminCount: number;
}): AdminRefusal | null {
  const demotion = input.currentRole === ADMIN_ROLE && input.newRole !== ADMIN_ROLE;
  if (!demotion) return null;
  if (input.actorId === input.targetId) return 'selfDemotion';
  if (input.adminCount <= 1) return 'lastAdmin';
  return null;
}
