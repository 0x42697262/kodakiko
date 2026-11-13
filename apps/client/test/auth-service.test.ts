import sodiumLib from 'libsodium-wrappers-sumo';
import { WORDLIST_EN } from '@kodakiko/core';
import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { decodeBytes, encodeBytes } from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  ARGON2ID_MIN_MEM_BYTES,
  concatBytes,
  utf8Encode,
  deriveLoginMaterial,
  openKeyBundle,
  wrapKey,
  type KdfParams,
  type Sodium,
} from '@kodakiko/core';
import { createMemoryFileStore } from '../src/adapters/memory/memory-file-store';
import { createMemoryGlobalStore } from '../src/adapters/memory/memory-global-store';
import { createMemoryRowStore } from '../src/adapters/memory/memory-row-store';
import { createMemorySessionStore } from '../src/adapters/memory/memory-session-store';
import { createKeyVault } from '../src/adapters/keyvault/keyvault.web';
import type { KeyVault } from '../src/adapters/keyvault/keyvault';
import { createRandomId } from '../src/adapters/ids';
import { createWrappersSodium } from '../src/adapters/sodium/sodium-wrappers';
import {
  createAccountState,
  createObservable,
  type AccountStateStore,
} from '../src/composition/account-state';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import { AuthError } from '../src/services/auth-errors';
import { createAuthService, type AuthServiceContext } from '../src/services/auth-service';
import { ROUTES } from '@kodakiko/contract';
import { createFakeAuthServer, bundleBytes, type FakeAuthServer } from './support/fake-auth-server';

const BASE_URL = 'https://journal.example.test';
const HOST = 'journal.example.test';
const USERNAME = 'alice';
const PASSWORD = 'correct horse battery staple';
const NEW_PASSWORD = 'another long passphrase here';
const FAKE_ARGON2_OPS = 2;
const FAKE_ARGON2_MEM_BYTES = 8_388_608;
const FLOOR_OPS = 32;
const MASTER_KEY_BYTES = 32;
const WORDS_PER_KEY = 24;

function floorParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: FLOOR_OPS, memLimitBytes: ARGON2ID_MIN_MEM_BYTES };
}

const KEY_LENGTH = 32;

async function createTestSodium(): Promise<{
  readonly sodium: Sodium;
  readonly argon2Calls: () => number;
  readonly secrets: Uint8Array[];
}> {
  await sodiumLib.ready;
  let calls = 0;
  const secrets: Uint8Array[] = [];
  const capture = (buffer: Uint8Array): Uint8Array => {
    if (buffer.length === KEY_LENGTH) secrets.push(buffer);
    return buffer;
  };
  const base = createWrappersSodium(sodiumLib, async (input) => {
    calls += 1;
    return capture(
      sodiumLib.crypto_pwhash(
        input.outputLength,
        concatBytes(input.password, utf8Encode(`${input.opsLimit}:${input.memLimitBytes}`)),
        input.salt,
        FAKE_ARGON2_OPS,
        FAKE_ARGON2_MEM_BYTES,
        sodiumLib.crypto_pwhash_ALG_ARGON2ID13,
      ),
    );
  });
  const sodium: Sodium = {
    ...base,
    kdfDerive: (length, id, context, key) => capture(base.kdfDerive(length, id, context, key)),
    aeadEncrypt: (plaintext, additionalData, nonce, key) => {
      capture(key);
      capture(plaintext);
      return base.aeadEncrypt(plaintext, additionalData, nonce, key);
    },
    aeadDecrypt: (ciphertext, additionalData, nonce, key) =>
      capture(base.aeadDecrypt(ciphertext, additionalData, nonce, key)),
    boxKeypair: () => {
      const pair = base.boxKeypair();
      capture(pair.privateKey);
      return pair;
    },
  };
  return { sodium, argon2Calls: () => calls, secrets };
}

function expectZeroed(secrets: readonly Uint8Array[], except: readonly Uint8Array[] = []): void {
  const leaked = secrets.filter(
    (buffer) => !except.includes(buffer) && buffer.some((byte) => byte !== 0),
  );
  expect(leaked).toHaveLength(0);
  expect(secrets.length).toBeGreaterThan(0);
}

type Device = {
  readonly rowStore: ReturnType<typeof createMemoryRowStore>;
  readonly fileStore: ReturnType<typeof createMemoryFileStore>;
  readonly sessionStore: ReturnType<typeof createMemorySessionStore>;
  readonly globalStore: ReturnType<typeof createMemoryGlobalStore>;
};

function createDevice(): Device {
  return {
    rowStore: createMemoryRowStore(),
    fileStore: createMemoryFileStore(),
    sessionStore: createMemorySessionStore(),
    globalStore: createMemoryGlobalStore(),
  };
}

async function startApp(
  server: FakeAuthServer,
  device: Device,
  sodium: Sodium,
  overrides: { readonly keyVault?: KeyVault; readonly noServer?: boolean } = {},
) {
  const accountState = createAccountState({ kind: 'signedOut' });
  const activeAccountKey = createObservable<string | null>(null);
  const http = server.http({
    sessionToken: async () => {
      const accountKey = activeAccountKey.get();
      return accountKey === null ? null : device.sessionStore.load(accountKey);
    },
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
  });
  const showSecuring = vi.fn();
  const choose = vi.fn();
  const accept = vi.fn();
  const inviteToken = createObservable<string | null>(null);
  const context: AuthServiceContext = {
    sodium,
    http,
    serverUrl: { current: async () => (overrides.noServer === true ? null : BASE_URL), choose },
    sessionStore: device.sessionStore,
    keyVault: overrides.keyVault ?? createKeyVault({ sodium }),
    rowStore: device.rowStore,
    fileStore: device.fileStore,
    globalStore: device.globalStore,
    lifecycle: {
      afterInteractions: async () => undefined,
      subscribeActive: () => () => undefined,
      subscribeOnline: () => () => undefined,
    },
    clock: { now: () => 0, nowIso: () => new Date(0).toISOString() },
    randomId: createRandomId(sodium),
    accountState,
    activeAccountKey,
    inviteToken,
    config: {
      get: () => null,
      refresh: async () => null,
      accept,
      subscribe: () => () => undefined,
    },
    deviceLabel: () => 'Test Device',
    kdfStartParams: floorParams,
    showSecuring,
    dismissSecuring: vi.fn(),
  };
  return {
    service: createAuthService(context),
    accountState,
    context,
    showSecuring,
    choose,
    accept,
    inviteToken,
  };
}

async function signUpAlice(server: FakeAuthServer, device: Device, sodium: Sodium) {
  const app = await startApp(server, device, sodium);
  const result = await app.service.signUp({
    username: USERNAME,
    password: PASSWORD,
    inviteToken: null,
    keepSignedIn: false,
  });
  return { ...app, recoveryWords: result.recoveryWords };
}

function accountKeyOf(server: FakeAuthServer): string {
  return `${HOST}/${server.user(USERNAME).id}`;
}

function failureReason(error: unknown): string {
  return error instanceof AuthError ? error.reason : String(error);
}

describe('auth service', () => {
  it('sign-up stores the kv data and the session and leaves the recovery key unconfirmed', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const { recoveryWords, accountState } = await signUpAlice(server, device, sodium);
    const accountKey = accountKeyOf(server);

    expect(recoveryWords).toHaveLength(24);
    expect(await device.sessionStore.load(accountKey)).toMatch(/^kdk_s_/);
    expect(await device.rowStore.kvGetJson(STORAGE_KEYS.accountUserId, z.string())).toBe(
      server.user(USERNAME).id,
    );
    expect(await device.rowStore.kvGetJson(STORAGE_KEYS.accountUsername, z.string())).toBe(
      USERNAME,
    );
    expect(
      await device.rowStore.kvGetJson(STORAGE_KEYS.accountRecoveryConfirmed, z.boolean()),
    ).toBe(false);
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek)).not.toBeNull();
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountRecoveryKeyByMaster)).not.toBeNull();
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountPrivateKeyByMaster)).not.toBeNull();
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountPublicKey)).not.toBeNull();
    expect(
      await device.rowStore.kvGetJson(STORAGE_KEYS.accountHomeCollectionId, z.string()),
    ).toEqual(expect.any(String));
    expect(accountState.get().kind).toBe('unlocked');
  });

  it('shows the securing screen before the key derivation and after the policy check', async () => {
    const { sodium, argon2Calls } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const app = await startApp(server, device, sodium);
    const argonCallsAtSecuring: number[] = [];
    app.showSecuring.mockImplementation(() => argonCallsAtSecuring.push(argon2Calls()));

    await expect(
      app.service.signUp({
        username: USERNAME,
        password: 'short',
        inviteToken: null,
        keepSignedIn: false,
      }),
    ).rejects.toMatchObject({ reason: 'passwordPolicy' });
    expect(app.showSecuring).not.toHaveBeenCalled();

    await app.service.signUp({
      username: USERNAME,
      password: PASSWORD,
      inviteToken: null,
      keepSignedIn: false,
    });
    expect(app.showSecuring).toHaveBeenCalledTimes(1);
    expect(argonCallsAtSecuring).toEqual([0]);
  });

  it('restore with a 401 clears the token and ends signed out', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    server.revokeSessions();

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();

    expect(restarted.accountState.get().kind).toBe('signedOut');
    expect(await device.sessionStore.load(accountKeyOf(server))).toBeNull();
  });

  it('restore without an active account ends signed out', async () => {
    const { sodium } = await createTestSodium();
    const app = await startApp(createFakeAuthServer(), createDevice(), sodium);
    await app.service.restore();
    expect(app.accountState.get().kind).toBe('signedOut');
  });

  it('restore offline with a stored session opens the stores and ends locked', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    server.online = false;

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();

    expect(restarted.accountState.get().kind).toBe('locked');
  });

  it('restore reports a pending deletion', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    server.markPendingDeletion(USERNAME);

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();
    expect(restarted.accountState.get().kind).toBe('pendingDeletion');
  });

  it('restore unlocks with the device key when the key vault is persistent', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const memory = createKeyVault({ sodium });
    const persistent: KeyVault = {
      ...memory,
      capabilities: async () => ({ persistent: true, biometric: true }),
    };
    const first = await startApp(server, device, sodium, { keyVault: persistent });
    await first.service.signUp({
      username: USERNAME,
      password: PASSWORD,
      inviteToken: null,
      keepSignedIn: true,
    });

    const restarted = await startApp(server, device, sodium, { keyVault: persistent });
    await restarted.service.restore();
    expect(restarted.accountState.get().kind).toBe('unlocked');
  });

  it('unlocks with the right password', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    server.online = false;

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();
    await restarted.service.unlockWithPassword(PASSWORD);

    const state = restarted.accountState.get();
    expect(state.kind).toBe('unlocked');
    expect(server.count(ROUTES.authKeyBundle())).toBe(0);
  });

  it('fetches the key bundle once after a wrong password and then reports wrongPassword', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();
    const failure = await restarted.service
      .unlockWithPassword('not the password at all')
      .catch((error: unknown) => error);

    expect(failureReason(failure)).toBe('wrongPassword');
    expect(server.count(ROUTES.authKeyBundle())).toBe(1);
    expect(restarted.accountState.get().kind).toBe('locked');
  });

  it('replaces a stale cached wrap with the fresh key bundle and unlocks', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    const stored = server.user(USERNAME);
    const userId = stored.id;
    const oldMaterial = await deriveLoginMaterial(sodium, PASSWORD, {
      ...floorParams(decodeBytes(stored.bundle.salt)),
    });
    const { masterKey } = openKeyBundle(
      sodium,
      oldMaterial.kek,
      userId,
      bundleBytes(stored.bundle),
    );
    const newSalt = sodium.randomBytes(16);
    const newMaterial = await deriveLoginMaterial(sodium, NEW_PASSWORD, floorParams(newSalt));
    const newWrap = wrapKey(sodium, newMaterial.kek, masterKey, AD_CONTEXTS.masterKeyByKek, userId);
    server.replaceBundle(USERNAME, newMaterial.loginKey, {
      ...stored.bundle,
      salt: encodeBytes(newSalt),
      masterKeyByKek: encodeBytes(newWrap),
    });

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();
    await restarted.service.unlockWithPassword(NEW_PASSWORD);

    expect(restarted.accountState.get().kind).toBe('unlocked');
    expect(server.count(ROUTES.authKeyBundle())).toBe(1);
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek)).toEqual(newWrap);
  });

  it('refuses hostile parameters before any Argon2id call', async () => {
    const { sodium, argon2Calls } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    const callsBefore = argon2Calls();
    server.hostileParams = true;

    const app = await startApp(server, createDevice(), sodium);
    const failure = await app.service
      .signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false })
      .catch((error: unknown) => error);

    expect(failureReason(failure)).toBe('unsafeServer');
    expect(argon2Calls()).toBe(callsBefore);
  });

  it('signs in with the right password and rejects wrong credentials with one answer', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    await signUpAlice(server, createDevice(), sodium);

    const wrong = await startApp(server, createDevice(), sodium);
    const wrongFailure = await wrong.service
      .signIn({ username: USERNAME, password: NEW_PASSWORD, keepSignedIn: false })
      .catch((error: unknown) => error);
    const unknown = await startApp(server, createDevice(), sodium);
    const unknownFailure = await unknown.service
      .signIn({ username: 'nobody-here', password: PASSWORD, keepSignedIn: false })
      .catch((error: unknown) => error);
    expect(failureReason(wrongFailure)).toBe('invalidCredentials');
    expect(failureReason(unknownFailure)).toBe('invalidCredentials');

    const right = await startApp(server, createDevice(), sodium);
    await right.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });
    expect(right.accountState.get().kind).toBe('unlocked');
  });

  it('signs in to an account with a pending deletion and reports it', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    await signUpAlice(server, createDevice(), sodium);
    server.requestDeletion(USERNAME);

    const app = await startApp(server, createDevice(), sodium);
    await app.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });
    expect(app.accountState.get().kind).toBe('pendingDeletion');
  });

  it('shows the recovery words again after a restart and an unlock, until confirmed', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const first = await signUpAlice(server, device, sodium);

    const restarted = await startApp(server, device, sodium);
    await restarted.service.restore();
    await restarted.service.unlockWithPassword(PASSWORD);
    expect(await restarted.service.pendingRecoveryWords()).toEqual(first.recoveryWords);

    await restarted.service.confirmRecoveryKeySaved();
    expect(await restarted.service.pendingRecoveryWords()).toBeNull();
    expect(
      await device.rowStore.kvGetJson(STORAGE_KEYS.accountRecoveryConfirmed, z.boolean()),
    ).toBe(true);
  });

  it('recovers with the 24 words and a new password', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const { recoveryWords } = await signUpAlice(server, createDevice(), sodium);
    const device = createDevice();
    const app = await startApp(server, device, sodium);

    await app.service.recover({
      username: USERNAME,
      words: recoveryWords.join('  ').toUpperCase(),
      newPassword: NEW_PASSWORD,
    });

    expect(app.accountState.get().kind).toBe('unlocked');
    expect(
      await device.rowStore.kvGetJson(STORAGE_KEYS.accountRecoveryConfirmed, z.boolean()),
    ).toBe(true);
    expect(await device.sessionStore.load(accountKeyOf(server))).toMatch(/^kdk_s_/);

    const second = await startApp(server, createDevice(), sodium);
    await second.service.signIn({
      username: USERNAME,
      password: NEW_PASSWORD,
      keepSignedIn: false,
    });
    expect(second.accountState.get().kind).toBe('unlocked');
    const third = await startApp(server, createDevice(), sodium);
    const oldFailure = await third.service
      .signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false })
      .catch((error: unknown) => error);
    expect(failureReason(oldFailure)).toBe('invalidCredentials');
  });

  it('reports the word position when a recovery word is not in the list', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const { recoveryWords } = await signUpAlice(server, createDevice(), sodium);
    const app = await startApp(server, createDevice(), sodium);
    const broken = recoveryWords.map((word, index) => (index === 4 ? 'zzzzzz' : word));

    const failure = await app.service
      .recover({ username: USERNAME, words: broken.join(' '), newPassword: NEW_PASSWORD })
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(AuthError);
    expect(failure).toMatchObject({ reason: 'recoveryWordsInvalid', position: 5 });
  });

  it('cancels a pending deletion and unlocks again', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    await signUpAlice(server, createDevice(), sodium);
    server.requestDeletion(USERNAME);
    const app = await startApp(server, createDevice(), sodium);
    await app.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });

    await app.service.cancelDeletion();

    expect(app.accountState.get().kind).toBe('unlocked');
    expect(server.count(ROUTES.usersMeDeleteCancel())).toBe(1);
  });

  it('sign-out wipes the keys in memory and destroys the stores', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const rowDestroy = vi.spyOn(device.rowStore, 'destroy');
    const fileDestroy = vi.spyOn(device.fileStore, 'destroy');
    const { service, accountState, context } = await signUpAlice(server, device, sodium);
    const forget = vi.spyOn(context.keyVault, 'forget');
    const state = accountState.get();
    if (state.kind !== 'unlocked') throw new Error('expected unlocked');
    const { keys } = state;
    expect(keys.masterKey.some((byte) => byte !== 0)).toBe(true);
    const accountKey = accountKeyOf(server);

    await service.signOut();

    expect(keys.masterKey).toEqual(new Uint8Array(MASTER_KEY_BYTES));
    expect(keys.privateKey.every((byte) => byte === 0)).toBe(true);
    expect(server.count(ROUTES.authSignOut())).toBe(1);
    expect(rowDestroy).toHaveBeenCalledWith(accountKey);
    expect(fileDestroy).toHaveBeenCalledWith(accountKey);
    expect(forget).toHaveBeenCalledWith(accountKey);
    expect(await device.sessionStore.load(accountKey)).toBeNull();
    expect(context.activeAccountKey.get()).toBeNull();
    expect(accountState.get().kind).toBe('signedOut');
  });

  it('sign-out ignores a failing server', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const { service, accountState } = await signUpAlice(server, device, sodium);
    server.online = false;
    await service.signOut();
    expect(accountState.get().kind).toBe('signedOut');
  });

  it('keeps the no-server state when the server cannot be reached', async () => {
    const { sodium } = await createTestSodium();
    const app = await startApp(createFakeAuthServer(), createDevice(), sodium);
    app.accountState.set({ kind: 'noServer' });
    app.choose.mockResolvedValue({ ok: false, reason: 'unreachable' });
    expect(await app.service.chooseServer('x')).toEqual({ ok: false, reason: 'unreachable' });
    expect(app.accountState.get().kind).toBe('noServer');
  });

  it('hands the chosen server config to the config service and leaves the no-server state', async () => {
    const { sodium } = await createTestSodium();
    const app = await startApp(createFakeAuthServer(), createDevice(), sodium);
    app.accountState.set({ kind: 'noServer' });
    const config = {
      apiVersion: 1,
      signupMode: 'invite',
      sharingEnabled: false,
      publicLinksEnabled: false,
      maxMediaPerEntry: 20,
      maxUploadBytes: 1000,
      defaultQuotaBytes: 0,
      trashDays: 30,
      accountDeletionGraceDays: 7,
      serverVersion: '0.1.0',
    };
    app.choose.mockResolvedValue({ ok: true, baseUrl: BASE_URL, config, inviteToken: null });

    await app.service.chooseServer('x');

    expect(app.accept).toHaveBeenCalledWith(config);
    expect(app.accountState.get().kind).toBe('signedOut');
  });
});

async function accountWithSecrets() {
  const { sodium, secrets } = await createTestSodium();
  const server = createFakeAuthServer();
  const first = await signUpAlice(server, createDevice(), sodium);
  secrets.length = 0;
  return { sodium, secrets, server, words: first.recoveryWords };
}

function liveKeys(app: { readonly accountState: AccountStateStore }): Uint8Array[] {
  const state = app.accountState.get();
  if (state.kind !== 'unlocked') throw new Error('expected unlocked');
  return [state.keys.masterKey, state.keys.privateKey];
}

describe('key hygiene', () => {
  it('sign-in leaves only the account keys unwiped after success', async () => {
    const { sodium, secrets, server } = await accountWithSecrets();
    const app = await startApp(server, createDevice(), sodium);
    await app.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });
    expectZeroed(secrets, liveKeys(app));
  });

  it('sign-in wipes the KEK and login key when the credentials are refused', async () => {
    const { sodium, secrets, server } = await accountWithSecrets();
    const app = await startApp(server, createDevice(), sodium);
    await expect(
      app.service.signIn({ username: USERNAME, password: NEW_PASSWORD, keepSignedIn: false }),
    ).rejects.toMatchObject({ reason: 'invalidCredentials' });
    expectZeroed(secrets);
  });

  it('sign-in wipes everything when the key bundle does not open', async () => {
    const { sodium, secrets, server } = await accountWithSecrets();
    const stored = server.user(USERNAME);
    const foreign = wrapKey(
      sodium,
      sodium.randomBytes(KEY_LENGTH),
      sodium.randomBytes(KEY_LENGTH),
      AD_CONTEXTS.masterKeyByKek,
      stored.id,
    );
    server.replaceBundle(USERNAME, null, {
      ...stored.bundle,
      masterKeyByKek: encodeBytes(foreign),
    });
    secrets.length = 0;
    const app = await startApp(server, createDevice(), sodium);

    await expect(
      app.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false }),
    ).rejects.toMatchObject({ reason: 'wrongPassword' });
    expectZeroed(secrets);
  });

  it('sign-in wipes the opened account keys when saving them fails', async () => {
    const { sodium, secrets, server } = await accountWithSecrets();
    const device = createDevice();
    vi.spyOn(device.rowStore, 'kvSetJson').mockRejectedValueOnce(new Error('disk full'));
    const app = await startApp(server, device, sodium);

    await expect(
      app.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false }),
    ).rejects.toThrow('disk full');
    expectZeroed(secrets);
  });

  it('sign-up leaves only the account keys unwiped after success', async () => {
    const { sodium, secrets } = await createTestSodium();
    const app = await startApp(createFakeAuthServer(), createDevice(), sodium);
    await app.service.signUp({
      username: USERNAME,
      password: PASSWORD,
      inviteToken: null,
      keepSignedIn: false,
    });
    expectZeroed(secrets, liveKeys(app));
  });

  it('sign-up wipes every secret when the server refuses it', async () => {
    const { sodium, secrets } = await createTestSodium();
    const server = createFakeAuthServer();
    server.failOnce(ROUTES.authSignUp(), 409, 'USERNAME_TAKEN');
    const app = await startApp(server, createDevice(), sodium);
    await expect(
      app.service.signUp({
        username: USERNAME,
        password: PASSWORD,
        inviteToken: null,
        keepSignedIn: false,
      }),
    ).rejects.toMatchObject({ reason: 'usernameTaken' });
    expectZeroed(secrets);
  });

  it('sign-up wipes every secret when saving the account fails', async () => {
    const { sodium, secrets } = await createTestSodium();
    const device = createDevice();
    vi.spyOn(device.rowStore, 'kvSetJson').mockRejectedValueOnce(new Error('disk full'));
    const app = await startApp(createFakeAuthServer(), device, sodium);
    await expect(
      app.service.signUp({
        username: USERNAME,
        password: PASSWORD,
        inviteToken: null,
        keepSignedIn: false,
      }),
    ).rejects.toThrow('disk full');
    expectZeroed(secrets);
  });

  it('recovery leaves only the account keys unwiped after success', async () => {
    const { sodium, secrets, server, words } = await accountWithSecrets();
    const app = await startApp(server, createDevice(), sodium);
    await app.service.recover({
      username: USERNAME,
      words: words.join(' '),
      newPassword: NEW_PASSWORD,
    });
    expectZeroed(secrets, liveKeys(app));
  });

  it('recovery wipes every secret when the server refuses the completion', async () => {
    const { sodium, secrets, server, words } = await accountWithSecrets();
    server.failOnce(ROUTES.authRecoveryComplete(), 500, 'INTERNAL');
    const app = await startApp(server, createDevice(), sodium);
    await expect(
      app.service.recover({
        username: USERNAME,
        words: words.join(' '),
        newPassword: NEW_PASSWORD,
      }),
    ).rejects.toMatchObject({ reason: 'generic' });
    expectZeroed(secrets);
  });

  it('recovery wipes the opened keys when the new password breaks the policy', async () => {
    const { sodium, secrets, server, words } = await accountWithSecrets();
    const app = await startApp(server, createDevice(), sodium);
    await expect(
      app.service.recover({ username: USERNAME, words: words.join(' '), newPassword: 'short' }),
    ).rejects.toMatchObject({ reason: 'passwordPolicy' });
    expectZeroed(secrets);
    expect(server.count(ROUTES.authRecoveryComplete())).toBe(0);
  });

  it('unlock with the password wipes the KEK and login key', async () => {
    const { sodium, secrets, server } = await accountWithSecrets();
    const device = createDevice();
    const first = await startApp(server, device, sodium);
    await first.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });
    const restarted = await startApp(server, device, sodium);
    server.online = false;
    await restarted.service.restore();
    secrets.length = 0;
    await restarted.service.unlockWithPassword(PASSWORD);
    expectZeroed(secrets, liveKeys(restarted));
  });

  it('unlock with the device wipes a master key that does not open the account', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const wrongKey = sodium.randomBytes(KEY_LENGTH);
    const memory = createKeyVault({ sodium });
    const vault: KeyVault = {
      ...memory,
      capabilities: async () => ({ persistent: true, biometric: true }),
      loadMasterKey: async () => wrongKey,
    };
    const first = await startApp(server, device, sodium, { keyVault: vault });
    await first.service.signUp({
      username: USERNAME,
      password: PASSWORD,
      inviteToken: null,
      keepSignedIn: true,
    });

    const restarted = await startApp(server, device, sodium, { keyVault: vault });
    await restarted.service.restore();

    expect(restarted.accountState.get().kind).toBe('locked');
    expect(wrongKey.every((byte) => byte === 0)).toBe(true);
  });
});

describe('review additions', () => {
  async function lockedOffline() {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    await signUpAlice(server, device, sodium);
    server.online = false;
    const app = await startApp(server, device, sodium);
    await app.service.restore();
    server.online = true;
    return { sodium, server, device, app };
  }

  it('a revoked session during the key-bundle refresh forces a full sign-in', async () => {
    const { server, device, app } = await lockedOffline();
    server.revokeSessions();

    await expect(app.service.unlockWithPassword(NEW_PASSWORD)).rejects.toMatchObject({
      reason: 'invalidCredentials',
    });

    expect(app.accountState.get()).toEqual({ kind: 'signedOut', username: USERNAME });
    expect(await device.sessionStore.load(accountKeyOf(server))).toBeNull();
  });

  it('keeps the cached bundle when the fresh bundle does not open either', async () => {
    const { sodium, server, device, app } = await lockedOffline();
    const stored = server.user(USERNAME);
    const cached = await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek);
    const foreign = wrapKey(
      sodium,
      sodium.randomBytes(KEY_LENGTH),
      sodium.randomBytes(KEY_LENGTH),
      AD_CONTEXTS.masterKeyByKek,
      stored.id,
    );
    server.replaceBundle(USERNAME, sodium.randomBytes(KEY_LENGTH), {
      ...stored.bundle,
      masterKeyByKek: encodeBytes(foreign),
    });

    await expect(app.service.unlockWithPassword('not the password at all')).rejects.toMatchObject({
      reason: 'wrongPassword',
    });

    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek)).toEqual(cached);
  });

  it('recovery decodes the words locally and sends no request for an invalid key', async () => {
    const { sodium, server, words } = await accountWithSecrets();
    const app = await startApp(server, createDevice(), sodium);
    const swapped = [...words];
    const lastWord = swapped.at(-1) ?? '';
    swapped[swapped.length - 1] = WORDLIST_EN[WORDLIST_EN.indexOf(lastWord) ^ 1] ?? '';

    await expect(
      app.service.recover({
        username: USERNAME,
        words: swapped.join(' '),
        newPassword: NEW_PASSWORD,
      }),
    ).rejects.toMatchObject({ reason: 'recoveryWordsInvalid' });
    await expect(
      app.service.recover({
        username: USERNAME,
        words: words.slice(1).join(' '),
        newPassword: NEW_PASSWORD,
      }),
    ).rejects.toMatchObject({ reason: 'recoveryWordsInvalid' });

    expect(server.count(ROUTES.authRecoveryStart())).toBe(0);
  });

  it('sign-out finishes every step even when some of them fail', async () => {
    const { sodium } = await createTestSodium();
    const server = createFakeAuthServer();
    const device = createDevice();
    const { service, accountState, context, inviteToken } = await signUpAlice(
      server,
      device,
      sodium,
    );
    inviteToken.set(`kdk_i_${'A'.repeat(43)}`);
    const accountKey = accountKeyOf(server);
    vi.spyOn(context.keyVault, 'forget').mockRejectedValue(new Error('vault'));
    vi.spyOn(device.rowStore, 'destroy').mockRejectedValue(new Error('rows'));
    const fileDestroy = vi.spyOn(device.fileStore, 'destroy');
    server.online = false;

    await service.signOut();

    expect(fileDestroy).toHaveBeenCalledWith(accountKey);
    expect(await device.sessionStore.load(accountKey)).toBeNull();
    expect(context.activeAccountKey.get()).toBeNull();
    expect(device.globalStore.get('kodakiko.activeAccount')).toBeNull();
    expect(accountState.get()).toEqual({ kind: 'signedOut' });
    expect(inviteToken.get()).toBeNull();
  });

  it('forgets the invite token after a successful sign-up', async () => {
    const { sodium } = await createTestSodium();
    const app = await startApp(createFakeAuthServer(), createDevice(), sodium);
    app.inviteToken.set(`kdk_i_${'A'.repeat(43)}`);
    await app.service.signUp({
      username: USERNAME,
      password: PASSWORD,
      inviteToken: null,
      keepSignedIn: false,
    });
    expect(app.inviteToken.get()).toBeNull();
  });

  it('recoveryPending reads only the confirmed flag and counts an error as pending', async () => {
    const { sodium } = await createTestSodium();
    const device = createDevice();
    const { service } = await signUpAlice(createFakeAuthServer(), device, sodium);
    expect(await service.recoveryPending()).toBe(true);

    vi.spyOn(device.rowStore, 'kvGetJson').mockRejectedValueOnce(new Error('unreadable'));
    expect(await service.recoveryPending()).toBe(true);

    await service.confirmRecoveryKeySaved();
    expect(await service.recoveryPending()).toBe(false);

    await device.rowStore.kvDelete(STORAGE_KEYS.accountRecoveryConfirmed);
    expect(await service.recoveryPending()).toBe(false);
  });
});

describe('settings additions (spec 20)', () => {
  it('changePassword sends the new material, replaces the session and stores the new parameters', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await signUpAlice(server, device, sodium);
    const accountKey = accountKeyOf(server);
    const before = await device.sessionStore.load(accountKey);
    const loginBefore = server.user(USERNAME).loginKey;
    await app.service.changePassword(PASSWORD, NEW_PASSWORD);
    expect(server.user(USERNAME).loginKey).not.toBe(loginBefore);
    expect(await device.sessionStore.load(accountKey)).not.toBe(before);
    expect(await app.service.verifyPassword(NEW_PASSWORD)).toBe(true);
    expect(await app.service.verifyPassword(PASSWORD)).toBe(false);
  });

  it('a wrong current password sends no request', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await signUpAlice(server, device, sodium);
    await expect(app.service.changePassword('wrong password entirely', NEW_PASSWORD)).rejects.toMatchObject({ reason: 'wrongPassword' });
    expect(server.count(ROUTES.authPassword())).toBe(0);
  });

  it('revealRecoveryWords returns the sign-up words for the right password only', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await signUpAlice(server, device, sodium);
    expect(await app.service.revealRecoveryWords(PASSWORD)).toEqual(app.recoveryWords);
    await expect(app.service.revealRecoveryWords('nope nope nope nope')).rejects.toMatchObject({ reason: 'wrongPassword' });
  });

  it('requestAccountDeletion signs out, keeps local data and remembers the date for sign-in', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await signUpAlice(server, device, sodium);
    const accountKey = accountKeyOf(server);
    const { deletionDueAt } = await app.service.requestAccountDeletion();
    expect(app.accountState.get()).toEqual({ kind: 'signedOut', username: USERNAME });
    expect(await device.sessionStore.load(accountKey)).toBeNull();
    expect(app.service.deletionScheduledFor()).toBe(deletionDueAt);
    expect(server.user(USERNAME).status).toBe('pending_deletion');
  });

  it('changeServer signs out and forgets the server', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await signUpAlice(server, device, sodium);
    const cleared = vi.fn(async () => undefined);
    (app.context.serverUrl as { clear?: () => Promise<void> }).clear = cleared;
    await app.service.changeServer();
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(app.accountState.get().kind).toBe('noServer');
    expect(device.globalStore.get('kodakiko.serverConfig')).toBeNull();
  });
});

const CONFIG = {
  apiVersion: 1,
  signupMode: 'invite',
  sharingEnabled: true,
  publicLinksEnabled: true,
  maxMediaPerEntry: 20,
  maxUploadBytes: 1000,
  defaultQuotaBytes: 0,
  trashDays: 30,
  accountDeletionGraceDays: 7,
  serverVersion: '0.1.0',
} as const;
const VALID_INVITE = `kdk_i_${'a'.repeat(43)}`;

async function localAlice(server: FakeAuthServer, device: Device, sodium: Sodium) {
  const app = await startApp(server, device, sodium, { noServer: true });
  app.accountState.set({ kind: 'noServer' });
  const result = await app.service.setUpLocal({ username: USERNAME, password: PASSWORD });
  return { ...app, recoveryWords: result.recoveryWords };
}

function localAccountKey(app: { readonly context: AuthServiceContext }): string {
  const key = app.context.activeAccountKey.get();
  if (key === null) throw new Error('expected an account key');
  return key;
}

describe('local-only mode (spec 20a)', () => {
  it('setUpLocal sends no request, stores the recovery wrap and the local mode, and ends unlocked', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await localAlice(server, device, sodium);
    expect(server.calls).toHaveLength(0);
    expect(app.recoveryWords).toHaveLength(WORDS_PER_KEY);
    expect(localAccountKey(app).startsWith('local/')).toBe(true);
    expect(await device.rowStore.kvGetJson(STORAGE_KEYS.accountMode, z.string())).toBe('local');
    expect(await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByRecovery)).not.toBeNull();
    expect(app.service.mode.get()).toBe('local');
    expect(app.accountState.get()).toMatchObject({ kind: 'unlocked', username: USERNAME });
  });

  it('restores without a server, unlocks with the password and never fetches a key bundle', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    await localAlice(server, device, sodium);
    const again = await startApp(server, device, sodium, { noServer: true });
    await again.service.restore();
    expect(again.accountState.get().kind).toBe('locked');
    expect(again.service.mode.get()).toBe('local');
    await expect(again.service.unlockWithPassword('wrong password entirely')).rejects.toMatchObject({ reason: 'wrongPassword' });
    await again.service.unlockWithPassword(PASSWORD);
    expect(again.accountState.get().kind).toBe('unlocked');
    expect(server.calls).toHaveLength(0);
  });

  it('local password change and local recovery change only the stored wrap and keep the master key', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await localAlice(server, device, sodium);
    const state = app.accountState.get();
    if (state.kind !== 'unlocked') throw new Error('expected unlocked');
    const masterKey = state.keys.masterKey.slice();
    await app.service.changePassword(PASSWORD, NEW_PASSWORD);
    expect(await app.service.verifyPassword(NEW_PASSWORD)).toBe(true);
    expect(await app.service.verifyPassword(PASSWORD)).toBe(false);
    await app.service.recover({ username: USERNAME, words: app.recoveryWords.join(' '), newPassword: PASSWORD });
    expect(await app.service.verifyPassword(PASSWORD)).toBe(true);
    const after = app.accountState.get();
    if (after.kind !== 'unlocked') throw new Error('expected unlocked');
    expect(after.keys.masterKey).toEqual(masterKey);
    expect(server.calls).toHaveLength(0);
  });

  it('connectServer registers the stored keys and ids and switches to server mode', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const app = await localAlice(server, device, sodium);
    const accountKey = localAccountKey(app);
    const userId = app.accountState.get().kind === 'unlocked' ? (app.accountState.get() as { userId: string }).userId : '';
    const wrapBefore = await device.rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByKek);
    app.choose.mockResolvedValue({ ok: true, baseUrl: BASE_URL, config: CONFIG, inviteToken: null });
    await app.service.connectServer({ serverInput: BASE_URL, inviteToken: VALID_INVITE, username: USERNAME, password: PASSWORD });
    const registered = server.user(USERNAME);
    expect(registered.id).toBe(userId);
    expect(registered.bundle.masterKeyByKek).toBe(encodeBytes(wrapBefore ?? new Uint8Array()));
    expect(app.service.mode.get()).toBe('server');
    expect(await device.rowStore.kvGetJson(STORAGE_KEYS.accountMode, z.string())).toBe('server');
    expect(app.context.activeAccountKey.get()).toBe(accountKey);
    expect(await device.sessionStore.load(accountKey)).not.toBeNull();
    expect(app.accept).toHaveBeenCalledWith(CONFIG);
    const relogin = await startApp(server, createDevice(), sodium);
    await relogin.service.signIn({ username: USERNAME, password: PASSWORD, keepSignedIn: false });
    expect(relogin.accountState.get()).toMatchObject({ kind: 'unlocked', userId });
  });

  it('a taken username leaves the account local', async () => {
    const server = createFakeAuthServer();
    const { sodium } = await createTestSodium();
    await signUpAlice(server, createDevice(), sodium);
    const device = createDevice();
    const app = await localAlice(server, device, sodium);
    app.choose.mockResolvedValue({ ok: true, baseUrl: BASE_URL, config: CONFIG, inviteToken: null });
    await expect(
      app.service.connectServer({ serverInput: BASE_URL, inviteToken: null, username: USERNAME, password: PASSWORD }),
    ).rejects.toMatchObject({ reason: 'usernameTaken' });
    expect(app.service.mode.get()).toBe('local');
    expect(await device.rowStore.kvGetJson(STORAGE_KEYS.accountMode, z.string())).toBe('local');
  });

  it('eraseLocal destroys the stores and forgets the account', async () => {
    const server = createFakeAuthServer();
    const device = createDevice();
    const { sodium } = await createTestSodium();
    const rowDestroy = vi.spyOn(device.rowStore, 'destroy');
    const app = await localAlice(server, device, sodium);
    const accountKey = localAccountKey(app);
    await app.service.eraseLocal();
    expect(rowDestroy).toHaveBeenCalledWith(accountKey);
    expect(device.globalStore.get('kodakiko.activeAccount')).toBeNull();
    expect(app.accountState.get().kind).toBe('noServer');
    expect(server.calls).toHaveLength(0);
  });
});
