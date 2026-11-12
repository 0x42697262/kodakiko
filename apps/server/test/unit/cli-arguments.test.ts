import { describe, expect, it } from 'vitest';
import { parseCreateInviteArguments } from '../../src/cli-arguments.ts';

describe('parseCreateInviteArguments', () => {
  it.each([
    [['--role', 'admin'], { role: 'admin', expiresInDays: null }],
    [['--role', 'member', '--expires-days', '3'], { role: 'member', expiresInDays: 3 }],
    [['--expires-days', '90', '--role', 'admin'], { role: 'admin', expiresInDays: 90 }],
    [['--role', 'admin', '--expires-days', '1'], { role: 'admin', expiresInDays: 1 }],
  ])('accepts %j', (args, expected) => {
    expect(parseCreateInviteArguments(args)).toEqual(expected);
  });

  it.each([
    [[]],
    [['--expires-days', '3']],
    [['--role', 'owner']],
    [['--role']],
    [['--role', 'admin', '--expires-days', '0']],
    [['--role', 'admin', '--expires-days', '91']],
    [['--role', 'admin', '--expires-days', 'x']],
    [['--role', 'admin', '--expires-days', '-1']],
    [['--role', 'admin', '--role', 'member']],
    [['--role', 'admin', '--other', '1']],
    [['admin']],
  ])('rejects %j', (args) => {
    expect(parseCreateInviteArguments(args)).toBeNull();
  });
});
