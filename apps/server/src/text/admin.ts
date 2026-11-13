export const ADMIN_CLI_TEXT = Object.freeze({
  usage:
    'Admin commands: list-users [--json] | set-quota --username <name> --bytes <n|default> | set-role --username <name> --role <admin|member> | list-invites [--json] | revoke-invite --id <inviteId> | stats [--json]',
  defaultQuota: 'default',
  never: 'never',
  open: 'open',
  revoked: 'revoked',
  expired: 'expired',
  alreadyClosed: 'already closed',
  unknownUser: '(deleted user)',
  none: '-',
  redeemedBy: (username: string) => `redeemed by ${username}`,
  refused: (reason: string) => `refused: ${reason}`,
});
