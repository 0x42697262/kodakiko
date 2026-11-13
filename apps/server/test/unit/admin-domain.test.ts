import { describe, expect, it } from 'vitest';
import { formatBytesIec, roleChangeRefusal } from '../../src/domain/admin.ts';

describe('formatBytesIec', () => {
  it.each([
    [0, '0 B'],
    [1023, '1023 B'],
    [1024, '1.0 KiB'],
    [1536, '1.5 KiB'],
    [1_073_741_824, '1.0 GiB'],
    [3_758_096_384, '3.5 GiB'],
  ])('%d → %s', (bytes, text) => {
    expect(formatBytesIec(bytes)).toBe(text);
  });
});

describe('roleChangeRefusal', () => {
  it('refuses self-demotion', () => {
    expect(roleChangeRefusal({ actorId: 'a', targetId: 'a', currentRole: 'admin', newRole: 'member', adminCount: 3 })).toBe('selfDemotion');
  });
  it('refuses demoting the last admin', () => {
    expect(roleChangeRefusal({ actorId: null, targetId: 'b', currentRole: 'admin', newRole: 'member', adminCount: 1 })).toBe('lastAdmin');
  });
  it('allows other changes', () => {
    expect(roleChangeRefusal({ actorId: 'a', targetId: 'b', currentRole: 'admin', newRole: 'member', adminCount: 2 })).toBeNull();
    expect(roleChangeRefusal({ actorId: 'a', targetId: 'b', currentRole: 'member', newRole: 'admin', adminCount: 1 })).toBeNull();
    expect(roleChangeRefusal({ actorId: 'a', targetId: 'a', currentRole: 'admin', newRole: 'admin', adminCount: 1 })).toBeNull();
  });
});
