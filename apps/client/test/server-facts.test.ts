import { describe, expect, it } from 'vitest';
import { serverFactSections } from '../src/features/settings/server-facts';

describe('serverFactSections', () => {
  const sections = serverFactSections({
    serverUrl: 'https://kodakiko.example.com',
    config: {
      apiVersion: 1, signupMode: 'invite', sharingEnabled: true, publicLinksEnabled: false, maxMediaPerEntry: 20,
      maxUploadBytes: 3_758_096_384, defaultQuotaBytes: 107_374_182_400, trashDays: 30, accountDeletionGraceDays: 7, serverVersion: '0.2.0',
    },
    user: { usedBytes: 1_073_741_824, quotaBytes: null },
    account: { userId: 'USER1', username: 'ada', opsLimit: 16, memLimitBytes: 268_435_456, recoveryConfirmed: true },
    fingerprint: '1234 5678',
    keyStorage: { persistent: true, biometric: true },
    encryptThumbnails: false,
    local: false,
  });
  const rows = Object.fromEntries(sections.flatMap((section) => section.rows.map((row) => [row.label, row.value])));

  it('describes the server, its features and its limits', () => {
    expect(rows['Address']).toBe('https://kodakiko.example.com');
    expect(rows['Server version']).toBe('0.2.0 (API 1)');
    expect(rows['Sign-up']).toBe('Invite only');
    expect(rows['Sharing']).toBe('On');
    expect(rows['Public links']).toBe('Off');
    expect(rows['Largest upload']).toBe('3.5 GB');
    expect(rows['Storage used']).toBe('1 GB of 100 GB');
  });

  it('describes the account keys and how they are stored', () => {
    expect(rows['Account id']).toBe('USER1');
    expect(rows['Security code']).toBe('1234 5678');
    expect(rows['Password hashing']).toBe('Argon2id, 16 passes, 256 MB');
    expect(rows['Recovery key']).toBe('Confirmed');
    expect(rows['Key on this device']).toBe('Kept, unlock with fingerprint or PIN');
    expect(rows['Media encryption']).toBe('XChaCha20-Poly1305, 1 MB chunks');
  });

  it('shows only the local facts in local mode', () => {
    const localOnly = serverFactSections({
      serverUrl: null, config: null, user: null,
      account: { userId: 'USER1', username: 'ada', opsLimit: 16, memLimitBytes: 268_435_456, recoveryConfirmed: false },
      fingerprint: '', keyStorage: { persistent: true, biometric: false }, encryptThumbnails: true, local: true,
    });
    expect(localOnly.map((section) => section.title)).not.toContain('Server');
  });
});
