import { describe, expect, it } from 'vitest';
import { deletionAllowed } from '../src/features/settings/delete-account-model';

describe('deletionAllowed', () => {
  it('needs the exact username and a password', () => {
    expect(deletionAllowed('ada', 'ada', 'pw')).toBe(true);
    expect(deletionAllowed('Ada', 'ada', 'pw')).toBe(false);
    expect(deletionAllowed('ad', 'ada', 'pw')).toBe(false);
    expect(deletionAllowed('ada', 'ada', '')).toBe(false);
    expect(deletionAllowed(' ada ', 'ada', 'pw')).toBe(true);
  });
});
