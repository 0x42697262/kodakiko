import { z } from 'zod';
import {
  decodeBytes,
  encodeBytes,
  idSchema,
  type KeyBundleDto,
  type SignInResponse,
  type UserDto,
} from '@kodakiko/contract';
import {
  AD_CONTEXTS,
  DecryptionError,
  createPasswordChangeMaterial,
  WrongPasswordError,
  checkPasswordPolicy,
  createRecoveryCompletion,
  createSignUpMaterial,
  decodeRecoveryWords,
  deriveForSignIn,
  rebuildSignUpRequest,
  type StoredLocalAccount,
  encodeRecoveryKey,
  isValidUsername,
  normalizeRecoveryInput,
  normalizeUsername,
  openKeyBundle,
  openRecovery,
  unwrapKey,
  wipe,
  type AccountKeys,
  type KdfParams,
  type KeyBundleBytes,
  type RecoveryCompleteBytes,
  type SignUpMaterial,
  type Sodium,
} from '@kodakiko/core';
import type { Clock } from '../adapters/clock';
import type { FileStore } from '../adapters/filestore/filestore';
import type { GlobalStore } from '../adapters/global-store/global-store';
import { ApiError, NetworkError, type HttpClient } from '../adapters/http/http';
import type { KeyVault } from '../adapters/keyvault/keyvault';
import type { Lifecycle } from '../adapters/lifecycle/lifecycle';
import type { RowStore } from '../adapters/rowstore/rowstore';
import type { ServerChoice, ServerUrlStore } from '../adapters/server-url/server-url';
import type { SessionStore } from '../adapters/session/session-store';
import { createObservable, type AccountStateStore, type Observable } from '../composition/account-state';
import { ACTIVE_ACCOUNT_SCHEMA_VERSION, LOCAL_ACCOUNT_KEY_PREFIX, type AccountMode } from '../constants/auth-constants';
import { HTTP_STATUS } from '../constants/http-constants';
import { ACCOUNT_KEY_SEPARATOR, GLOBAL_STORE_KEYS } from '../keys/auth-keys';
import { STORAGE_KEYS } from '../keys/storage-keys';
import { EN } from '../text/en';
import {
  loadAccount,
  saveAccountData,
  saveDeletionDueAt,
  saveKeyBundle,
  saveRole,
  type StoredAccount,
} from './account-storage';
import { AUTH_CALLS } from './auth-calls';
import { AuthError, toAuthError } from './auth-errors';
import type { ConfigService } from './config/config-service';

export type AuthService = {
  readonly restore: () => Promise<void>;
  readonly chooseServer: (input: string) => Promise<ServerChoice>;
  readonly signUp: (input: {
    username: string;
    password: string;
    inviteToken: string | null;
    keepSignedIn: boolean;
  }) => Promise<{ recoveryWords: readonly string[] }>;
  readonly confirmRecoveryKeySaved: () => Promise<void>;
  readonly signIn: (input: {
    username: string;
    password: string;
    keepSignedIn: boolean;
  }) => Promise<void>;
  readonly unlockWithPassword: (password: string) => Promise<void>;
  readonly unlockWithDevice: () => Promise<boolean>;
  readonly recover: (input: {
    username: string;
    words: string;
    newPassword: string;
  }) => Promise<void>;
  readonly pendingRecoveryWords: () => Promise<readonly string[] | null>;
  readonly recoveryPending: () => Promise<boolean>;
  readonly cancelDeletion: () => Promise<void>;
  readonly signOut: () => Promise<void>;
  readonly setKeepUnlocked: (enabled: boolean) => Promise<void>;
  readonly keepUnlocked: () => Promise<boolean>;
  readonly activeUsername: () => string | null;
  readonly changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
  readonly revealRecoveryWords: (password: string) => Promise<readonly string[]>;
  readonly verifyPassword: (password: string) => Promise<boolean>;
  readonly requestAccountDeletion: () => Promise<{ readonly deletionDueAt: string }>;
  readonly deletionScheduledFor: () => string | null;
  readonly changeServer: () => Promise<void>;
  readonly setUpLocal: (input: { readonly username: string; readonly password: string }) => Promise<{ recoveryWords: readonly string[] }>;
  readonly connectServer: (input: {
    readonly serverInput: string;
    readonly inviteToken: string | null;
    readonly username: string;
    readonly password: string;
  }) => Promise<void>;
  readonly eraseLocal: () => Promise<void>;
  readonly mode: Observable<AccountMode>;
};

export type AuthServiceContext = {
  readonly sodium: Sodium;
  readonly http: HttpClient;
  readonly serverUrl: ServerUrlStore;
  readonly sessionStore: SessionStore;
  readonly keyVault: KeyVault;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly globalStore: GlobalStore;
  readonly lifecycle: Lifecycle;
  readonly clock: Clock;
  readonly randomId: () => string;
  readonly accountState: AccountStateStore;
  readonly activeAccountKey: Observable<string | null>;
  readonly config: ConfigService;
  readonly inviteToken: Observable<string | null>;
  readonly deviceLabel: () => string;
  readonly kdfStartParams: (salt: Uint8Array) => KdfParams;
  readonly showSecuring: () => void;
  readonly dismissSecuring: () => void;
  readonly beforeSignOut?: () => Promise<void>;
  readonly accountMode?: Observable<AccountMode>;
};

const activeAccountSchema = z.object({
  schemaVersion: z.number().int(),
  accountKey: z.string(),
  username: z.string(),
  mode: z.enum(['local', 'server']).optional(),
});

type ActiveAccount = z.infer<typeof activeAccountSchema>;

type EstablishInput = {
  readonly user: UserDto;
  readonly token: string;
  readonly keepSignedIn: boolean;
  readonly bundle: KeyBundleBytes;
  readonly keys: AccountKeys;
  readonly recoveryConfirmed: boolean;
  readonly homeCollectionId: string | null;
};

function wipeKeys(keys: Pick<AccountKeys, 'masterKey' | 'privateKey'>): void {
  wipe(keys.masterKey);
  wipe(keys.privateKey);
}

function bundleBytes(bundle: KeyBundleDto): KeyBundleBytes {
  return {
    salt: decodeBytes(bundle.salt),
    opsLimit: bundle.opsLimit,
    memLimitBytes: bundle.memLimitBytes,
    masterKeyByKek: decodeBytes(bundle.masterKeyByKek),
    recoveryKeyByMaster: decodeBytes(bundle.recoveryKeyByMaster),
    publicKey: decodeBytes(bundle.publicKey),
    privateKeyByMaster: decodeBytes(bundle.privateKeyByMaster),
  };
}

function kdfParamsOf(bundle: KeyBundleBytes): KdfParams {
  return { salt: bundle.salt, opsLimit: bundle.opsLimit, memLimitBytes: bundle.memLimitBytes };
}

function requirePolicy(password: string, username: string): void {
  const rules = checkPasswordPolicy(password, username);
  if (rules.length > 0) throw new AuthError('passwordPolicy', { rules });
}

export function createAuthService(context: AuthServiceContext): AuthService {
  const { sodium, http, rowStore, fileStore, sessionStore, keyVault, accountState } = context;
  const accountMode = context.accountMode ?? createObservable<AccountMode>('server');

  let previousState = accountState.get();
  accountState.subscribe(() => {
    const next = accountState.get();
    if (previousState.kind === 'unlocked') {
      const keysChanged = next.kind !== 'unlocked' || next.keys !== previousState.keys;
      if (keysChanged) wipeKeys(previousState.keys);
    }
    previousState = next;
  });

  function readActiveAccount(): ActiveAccount | null {
    const stored = context.globalStore.get(GLOBAL_STORE_KEYS.activeAccount);
    if (stored === null) return null;
    try {
      const parsed = activeAccountSchema.safeParse(JSON.parse(stored));
      return parsed.success && parsed.data.schemaVersion <= ACTIVE_ACCOUNT_SCHEMA_VERSION
        ? parsed.data
        : null;
    } catch {
      return null;
    }
  }

  function writeActiveAccount(accountKey: string, username: string, mode: AccountMode = 'server'): void {
    const value: ActiveAccount = {
      schemaVersion: ACTIVE_ACCOUNT_SCHEMA_VERSION,
      accountKey,
      username,
      mode,
    };
    accountMode.set(mode);
    context.globalStore.set(GLOBAL_STORE_KEYS.activeAccount, JSON.stringify(value));
  }

  function setSignedOut(username: string | null): void {
    accountState.set(username === null ? { kind: 'signedOut' } : { kind: 'signedOut', username });
  }

  async function accountKeyFor(userId: string): Promise<string> {
    const baseUrl = await context.serverUrl.current();
    if (baseUrl === null) throw new AuthError('generic');
    return `${new URL(baseUrl).host}${ACCOUNT_KEY_SEPARATOR}${userId}`;
  }

  async function openStores(accountKey: string): Promise<void> {
    await rowStore.open(accountKey);
    await fileStore.open(accountKey);
  }

  function requireAccountKey(): string {
    const accountKey = context.activeAccountKey.get();
    if (accountKey === null) throw new AuthError('generic');
    return accountKey;
  }

  function unlockedState(account: { userId: string; username: string }, keys: AccountKeys) {
    accountState.set({
      kind: 'unlocked',
      keys,
      userId: account.userId,
      username: account.username,
    });
  }

  function unwrapPrivateKey(masterKey: Uint8Array, userId: string, wrapped: Uint8Array) {
    return unwrapKey(sodium, masterKey, wrapped, AD_CONTEXTS.privateKeyByMaster, userId);
  }

  async function persistAccount(input: EstablishInput): Promise<void> {
    const accountKey = await accountKeyFor(input.user.id);
    await sessionStore.save(accountKey, input.token, input.keepSignedIn);
    context.activeAccountKey.set(accountKey);
    writeActiveAccount(accountKey, input.user.username);
    await openStores(accountKey);
    await saveAccountData(rowStore, {
      userId: input.user.id,
      username: input.user.username,
      bundle: input.bundle,
      recoveryConfirmed: input.recoveryConfirmed,
      homeCollectionId: input.homeCollectionId,
      deletionDueAt: input.user.deletionDueAt,
      role: input.user.role,
    });
    await keyVault.storeMasterKey(accountKey, input.keys.masterKey);
  }

  async function establish(input: EstablishInput): Promise<void> {
    try {
      await persistAccount(input);
    } catch (error) {
      wipeKeys(input.keys);
      throw error;
    }
    if (input.user.status === 'pending_deletion') {
      wipeKeys(input.keys);
      accountState.set({ kind: 'pendingDeletion' });
    } else {
      unlockedState({ userId: input.user.id, username: input.user.username }, input.keys);
    }
  }

  async function establishFromSignIn(
    response: SignInResponse,
    keys: AccountKeys,
    keepSignedIn: boolean,
  ): Promise<void> {
    await establish({
      user: response.user,
      token: response.session.token,
      keepSignedIn,
      bundle: bundleBytes(response.keyBundle),
      keys,
      recoveryConfirmed: true,
      homeCollectionId: null,
    });
  }

  async function unlockWithDevice(): Promise<boolean> {
    const accountKey = context.activeAccountKey.get();
    if (accountKey === null) return false;
    const stored = await loadAccount(rowStore);
    if (stored === null) return false;
    const masterKey = await keyVault.loadMasterKey(accountKey, EN['lock.biometricPrompt']);
    if (masterKey === null) return false;
    let privateKey: Uint8Array;
    try {
      privateKey = unwrapPrivateKey(masterKey, stored.userId, stored.bundle.privateKeyByMaster);
    } catch (error) {
      wipe(masterKey);
      if (error instanceof DecryptionError) return false;
      throw error;
    }
    unlockedState(stored, { masterKey, privateKey, publicKey: stored.bundle.publicKey });
    return true;
  }

  function openWithPassword(
    password: string,
    userId: string,
    bundle: KeyBundleBytes,
  ): Promise<AccountKeys> {
    return deriveForSignIn(sodium, password, kdfParamsOf(bundle)).then(({ kek, loginKey }) => {
      wipe(loginKey);
      try {
        return openKeyBundle(sodium, kek, userId, bundle);
      } finally {
        wipe(kek);
      }
    });
  }

  async function fetchFreshBundle(
    accountKey: string,
    username: string,
  ): Promise<KeyBundleBytes | null> {
    try {
      return bundleBytes(await http.request(AUTH_CALLS.keyBundle()));
    } catch (error) {
      if (error instanceof ApiError && error.status === HTTP_STATUS.unauthorized) {
        await sessionStore.clear(accountKey);
        setSignedOut(username);
        throw new AuthError('invalidCredentials');
      }
      return null;
    }
  }

  async function unlockWithPassword(password: string): Promise<void> {
    const stored = await loadAccount(rowStore);
    if (stored === null) throw new AuthError('generic');
    const accountKey = requireAccountKey();
    let keys: AccountKeys;
    try {
      try {
        keys = await openWithPassword(password, stored.userId, stored.bundle);
      } catch (error) {
        if (!(error instanceof WrongPasswordError) || accountMode.get() === 'local') throw error;
        const fresh = await fetchFreshBundle(accountKey, stored.username);
        if (fresh === null) throw error;
        keys = await openWithPassword(password, stored.userId, fresh);
        await saveKeyBundle(rowStore, fresh);
      }
    } catch (error) {
      throw toAuthError(error);
    }
    await keyVault.storeMasterKey(accountKey, keys.masterKey);
    unlockedState(stored, keys);
  }

  async function currentSessionUser(): Promise<UserDto | null> {
    try {
      return (await http.request(AUTH_CALLS.session())).user;
    } catch (error) {
      if (error instanceof ApiError && error.status === HTTP_STATUS.unauthorized) {
        throw error;
      }
      if (error instanceof NetworkError || error instanceof ApiError) return null;
      throw error;
    }
  }

  async function restore(): Promise<void> {
    const remembered = readActiveAccount();
    if (remembered?.mode === 'local') {
      context.activeAccountKey.set(remembered.accountKey);
      accountMode.set('local');
      await openStores(remembered.accountKey);
      accountState.set({ kind: 'locked' });
      await unlockWithDevice();
      return;
    }
    if ((await context.serverUrl.current()) === null) {
      accountState.set({ kind: 'noServer' });
      return;
    }
    const active = readActiveAccount();
    if (active === null) {
      setSignedOut(null);
      return;
    }
    context.activeAccountKey.set(active.accountKey);
    if ((await sessionStore.load(active.accountKey)) === null) {
      setSignedOut(active.username);
      return;
    }
    let user: UserDto | null;
    try {
      user = await currentSessionUser();
    } catch (error) {
      if (!(error instanceof ApiError)) throw error;
      await sessionStore.clear(active.accountKey);
      setSignedOut(active.username);
      return;
    }
    await openStores(active.accountKey);
    if (user?.status === 'pending_deletion') {
      await saveDeletionDueAt(rowStore, user.deletionDueAt);
      accountState.set({ kind: 'pendingDeletion' });
      return;
    }
    if (user !== null) await saveDeletionDueAt(rowStore, null);
    accountState.set({ kind: 'locked' });
    await unlockWithDevice();
  }

  async function chooseServer(input: string): Promise<ServerChoice> {
    const choice = await context.serverUrl.choose(input);
    if (!choice.ok) return choice;
    context.config.accept(choice.config);
    if (accountState.get().kind === 'noServer') setSignedOut(null);
    return choice;
  }

  async function signUp(input: {
    username: string;
    password: string;
    inviteToken: string | null;
    keepSignedIn: boolean;
  }): Promise<{ recoveryWords: readonly string[] }> {
    const username = normalizeUsername(input.username);
    if (!isValidUsername(username)) throw new AuthError('usernameInvalid');
    requirePolicy(input.password, username);
    context.showSecuring();
    await context.lifecycle.afterInteractions();
    let material: SignUpMaterial | null = null;
    try {
      material = await createSignUpMaterial(
        sodium,
        {
          username,
          password: input.password,
          homeCollectionName: EN['defaults.homeCollectionName'],
          createdAtUtc: context.clock.nowIso(),
          randomId: context.randomId,
        },
        context.kdfStartParams,
      );
      const { request, secrets } = material;
      const response = await http.request(
        AUTH_CALLS.signUp({
          userId: idSchema.parse(request.userId),
          username: request.username,
          inviteToken: input.inviteToken,
          deviceLabel: context.deviceLabel(),
          salt: encodeBytes(request.salt),
          opsLimit: request.opsLimit,
          memLimitBytes: request.memLimitBytes,
          loginKey: encodeBytes(request.loginKey),
          recoveryLoginKey: encodeBytes(request.recoveryLoginKey),
          publicKey: encodeBytes(request.publicKey),
          masterKeyByKek: encodeBytes(request.masterKeyByKek),
          masterKeyByRecovery: encodeBytes(request.masterKeyByRecovery),
          recoveryKeyByMaster: encodeBytes(request.recoveryKeyByMaster),
          privateKeyByMaster: encodeBytes(request.privateKeyByMaster),
          homeCollection: {
            id: idSchema.parse(request.homeCollection.id),
            keyByOwner: encodeBytes(request.homeCollection.keyByOwner),
            metadataBlob: encodeBytes(request.homeCollection.metadataBlob),
          },
        }),
      );
      await establish({
        user: response.user,
        token: response.session.token,
        keepSignedIn: input.keepSignedIn,
        bundle: {
          salt: request.salt,
          opsLimit: request.opsLimit,
          memLimitBytes: request.memLimitBytes,
          masterKeyByKek: request.masterKeyByKek,
          recoveryKeyByMaster: request.recoveryKeyByMaster,
          publicKey: request.publicKey,
          privateKeyByMaster: request.privateKeyByMaster,
        },
        keys: {
          masterKey: secrets.masterKey,
          privateKey: secrets.privateKey,
          publicKey: secrets.publicKey,
        },
        recoveryConfirmed: false,
        homeCollectionId: secrets.homeCollectionId,
      });
      context.inviteToken.set(null);
      return { recoveryWords: material.recoveryWords };
    } catch (error) {
      if (material !== null) wipeKeys(material.secrets);
      context.dismissSecuring();
      throw toAuthError(error);
    } finally {
      if (material !== null) {
        wipe(material.request.loginKey);
        wipe(material.request.recoveryLoginKey);
        wipe(material.secrets.homeCollectionKey);
      }
    }
  }

  async function confirmRecoveryKeySaved(): Promise<void> {
    await rowStore.kvSetJson(STORAGE_KEYS.accountRecoveryConfirmed, true);
    const state = accountState.get();
    if (state.kind === 'unlocked') accountState.set(state);
  }

  async function signIn(input: {
    username: string;
    password: string;
    keepSignedIn: boolean;
  }): Promise<void> {
    const username = normalizeUsername(input.username);
    try {
      const params = await http.request(AUTH_CALLS.params({ username }));
      const { kek, loginKey } = await deriveForSignIn(sodium, input.password, {
        salt: decodeBytes(params.salt),
        opsLimit: params.opsLimit,
        memLimitBytes: params.memLimitBytes,
      });
      try {
        const response = await http.request(
          AUTH_CALLS.signIn({
            username,
            loginKey: encodeBytes(loginKey),
            deviceLabel: context.deviceLabel(),
          }),
        );
        const keys = openKeyBundle(sodium, kek, response.user.id, bundleBytes(response.keyBundle));
        await establishFromSignIn(response, keys, input.keepSignedIn);
      } finally {
        wipe(kek);
        wipe(loginKey);
      }
    } catch (error) {
      throw toAuthError(error);
    }
  }

  async function recover(input: {
    username: string;
    words: string;
    newPassword: string;
  }): Promise<void> {
    if (accountMode.get() === 'local') {
      await recoverLocal(input.words, input.newPassword);
      return;
    }
    const username = normalizeUsername(input.username);
    let opened: { masterKey: Uint8Array; recoveryKey: Uint8Array } | null = null;
    let completion: RecoveryCompleteBytes | null = null;
    try {
      const words = normalizeRecoveryInput(input.words);
      wipe(decodeRecoveryWords(sodium, words));
      const start = await http.request(AUTH_CALLS.recoveryStart({ username }));
      opened = openRecovery(sodium, words, start.userId, decodeBytes(start.masterKeyByRecovery));
      requirePolicy(input.newPassword, username);
      completion = await createRecoveryCompletion(
        sodium,
        {
          userId: start.userId,
          newPassword: input.newPassword,
          masterKey: opened.masterKey,
          recoveryKey: opened.recoveryKey,
        },
        context.kdfStartParams,
      );
      const response = await http.request(
        AUTH_CALLS.recoveryComplete({
          username,
          recoveryLoginKey: encodeBytes(completion.recoveryLoginKey),
          deviceLabel: context.deviceLabel(),
          salt: encodeBytes(completion.salt),
          opsLimit: completion.opsLimit,
          memLimitBytes: completion.memLimitBytes,
          loginKey: encodeBytes(completion.loginKey),
          masterKeyByKek: encodeBytes(completion.masterKeyByKek),
        }),
      );
      const bundle = bundleBytes(response.keyBundle);
      const privateKey = unwrapPrivateKey(
        opened.masterKey,
        response.user.id,
        bundle.privateKeyByMaster,
      );
      await establish({
        user: response.user,
        token: response.session.token,
        keepSignedIn: false,
        bundle,
        keys: { masterKey: opened.masterKey, privateKey, publicKey: bundle.publicKey },
        recoveryConfirmed: true,
        homeCollectionId: null,
      });
    } catch (error) {
      if (opened !== null) wipe(opened.masterKey);
      throw toAuthError(error);
    } finally {
      if (opened !== null) wipe(opened.recoveryKey);
      if (completion !== null) {
        wipe(completion.loginKey);
        wipe(completion.recoveryLoginKey);
      }
    }
  }

  async function pendingRecoveryWords(): Promise<readonly string[] | null> {
    const state = accountState.get();
    if (state.kind !== 'unlocked') return null;
    const stored: StoredAccount | null = await loadAccount(rowStore);
    if (stored === null || stored.recoveryConfirmed) return null;
    const recoveryKey = unwrapKey(
      sodium,
      state.keys.masterKey,
      stored.bundle.recoveryKeyByMaster,
      AD_CONTEXTS.recoveryKeyByMaster,
      stored.userId,
    );
    try {
      return encodeRecoveryKey(sodium, recoveryKey);
    } finally {
      wipe(recoveryKey);
    }
  }

  async function cancelDeletion(): Promise<void> {
    try {
      await http.request(AUTH_CALLS.cancelDeletion());
    } catch (error) {
      throw toAuthError(error);
    }
    await restore();
  }

  async function attempt(step: () => Promise<unknown>): Promise<void> {
    try {
      await step();
    } catch {
      return;
    }
  }

  let deletionNotice: string | null = null;

  async function requireStored() {
    const stored = await loadAccount(rowStore);
    if (stored === null) throw new AuthError('generic');
    return stored;
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    const stored = await requireStored();
    requirePolicy(newPassword, stored.username);
    const accountKey = requireAccountKey();
    let material;
    try {
      material = await createPasswordChangeMaterial(
        sodium,
        {
          userId: stored.userId,
          currentPassword,
          newPassword,
          currentParams: kdfParamsOf(stored.bundle),
          cachedMasterKeyByKek: stored.bundle.masterKeyByKek,
        },
        context.kdfStartParams,
      );
    } catch (error) {
      throw toAuthError(error);
    }
    if (accountMode.get() === 'local') {
      try {
        await saveKeyBundle(rowStore, {
          ...stored.bundle,
          salt: material.salt,
          opsLimit: material.opsLimit,
          memLimitBytes: material.memLimitBytes,
          masterKeyByKek: material.masterKeyByKek,
        });
      } finally {
        wipe(material.currentLoginKey);
        wipe(material.loginKey);
      }
      return;
    }
    try {
      const response = await http.request(
        AUTH_CALLS.changePassword({
          currentLoginKey: encodeBytes(material.currentLoginKey),
          salt: encodeBytes(material.salt),
          opsLimit: material.opsLimit,
          memLimitBytes: material.memLimitBytes,
          loginKey: encodeBytes(material.loginKey),
          masterKeyByKek: encodeBytes(material.masterKeyByKek),
        }),
      );
      const persist = (await keyVault.capabilities(accountKey)).persistent;
      await sessionStore.save(accountKey, response.session.token, persist);
      await saveKeyBundle(rowStore, {
        ...stored.bundle,
        salt: material.salt,
        opsLimit: material.opsLimit,
        memLimitBytes: material.memLimitBytes,
        masterKeyByKek: material.masterKeyByKek,
      });
    } catch (error) {
      throw toAuthError(error);
    } finally {
      wipe(material.currentLoginKey);
      wipe(material.loginKey);
    }
  }

  async function revealRecoveryWords(password: string): Promise<readonly string[]> {
    const stored = await requireStored();
    let keys: AccountKeys;
    try {
      keys = await openWithPassword(password, stored.userId, stored.bundle);
    } catch (error) {
      throw toAuthError(error);
    }
    try {
      const recoveryKey = unwrapKey(sodium, keys.masterKey, stored.bundle.recoveryKeyByMaster, AD_CONTEXTS.recoveryKeyByMaster, stored.userId);
      try {
        return encodeRecoveryKey(sodium, recoveryKey);
      } finally {
        wipe(recoveryKey);
      }
    } finally {
      wipeKeys(keys);
    }
  }

  async function verifyPassword(password: string): Promise<boolean> {
    const stored = await requireStored();
    try {
      wipeKeys(await openWithPassword(password, stored.userId, stored.bundle));
      return true;
    } catch (error) {
      if (error instanceof WrongPasswordError || error instanceof DecryptionError) return false;
      throw error;
    }
  }

  async function requestAccountDeletion(): Promise<{ readonly deletionDueAt: string }> {
    const stored = await requireStored();
    const accountKey = requireAccountKey();
    let response;
    try {
      response = await http.request(AUTH_CALLS.requestDeletion());
    } catch (error) {
      throw toAuthError(error);
    }
    deletionNotice = response.deletionDueAt;
    await saveDeletionDueAt(rowStore, response.deletionDueAt);
    await attempt(() => sessionStore.clear(accountKey));
    setSignedOut(stored.username);
    return { deletionDueAt: response.deletionDueAt };
  }

  async function changeServer(): Promise<void> {
    await signOut();
    await attempt(() => context.serverUrl.clear?.() ?? Promise.resolve());
    await attempt(() => context.globalStore.delete(GLOBAL_STORE_KEYS.serverConfig));
    deletionNotice = null;
    accountState.set({ kind: 'noServer' });
  }

  async function setUpLocal(input: { readonly username: string; readonly password: string }): Promise<{ recoveryWords: readonly string[] }> {
    const username = normalizeUsername(input.username);
    if (!isValidUsername(username)) throw new AuthError('usernameInvalid');
    requirePolicy(input.password, username);
    context.showSecuring();
    await context.lifecycle.afterInteractions();
    let material: SignUpMaterial | null = null;
    try {
      material = await createSignUpMaterial(
        sodium,
        {
          username,
          password: input.password,
          homeCollectionName: EN['defaults.homeCollectionName'],
          createdAtUtc: context.clock.nowIso(),
          randomId: context.randomId,
        },
        context.kdfStartParams,
      );
      const { request, secrets } = material;
      const accountKey = `${LOCAL_ACCOUNT_KEY_PREFIX}${ACCOUNT_KEY_SEPARATOR}${request.userId}`;
      context.activeAccountKey.set(accountKey);
      await openStores(accountKey);
      await saveAccountData(rowStore, {
        userId: request.userId,
        username,
        bundle: {
          salt: request.salt,
          opsLimit: request.opsLimit,
          memLimitBytes: request.memLimitBytes,
          masterKeyByKek: request.masterKeyByKek,
          recoveryKeyByMaster: request.recoveryKeyByMaster,
          publicKey: request.publicKey,
          privateKeyByMaster: request.privateKeyByMaster,
        },
        recoveryConfirmed: false,
        homeCollectionId: secrets.homeCollectionId,
        deletionDueAt: null,
      });
      await rowStore.kvSet(STORAGE_KEYS.accountMasterKeyByRecovery, request.masterKeyByRecovery);
      await rowStore.kvSetJson(STORAGE_KEYS.accountMode, 'local');
      await rowStore.transaction((tx) =>
        tx.collections.put({
          id: request.homeCollection.id,
          kind: 'home',
          ownerId: request.userId,
          ownerUsername: username,
          role: 'owner',
          keyMaterial: request.homeCollection.keyByOwner,
          metadataBlob: request.homeCollection.metadataBlob,
          metaUpdatedAt: context.clock.nowIso(),
          serverLastSeq: 0,
          cursorSeq: 0,
        }),
      );
      await keyVault.storeMasterKey(accountKey, secrets.masterKey);
      writeActiveAccount(accountKey, username, 'local');
      unlockedState({ userId: request.userId, username }, {
        masterKey: secrets.masterKey,
        privateKey: secrets.privateKey,
        publicKey: secrets.publicKey,
      });
      return { recoveryWords: material.recoveryWords };
    } catch (error) {
      if (material !== null) wipeKeys(material.secrets);
      context.dismissSecuring();
      throw toAuthError(error);
    } finally {
      if (material !== null) {
        wipe(material.request.loginKey);
        wipe(material.request.recoveryLoginKey);
        wipe(material.secrets.homeCollectionKey);
      }
    }
  }

  async function storedLocalAccount(): Promise<StoredLocalAccount> {
    const stored = await requireStored();
    const masterKeyByRecovery = await rowStore.kvGet(STORAGE_KEYS.accountMasterKeyByRecovery);
    const homeId = await rowStore.kvGetJson(STORAGE_KEYS.accountHomeCollectionId, z.string());
    const home = homeId === null ? null : await rowStore.transaction((tx) => tx.collections.get(homeId));
    if (masterKeyByRecovery === null || home === null) throw new AuthError('generic');
    return {
      userId: stored.userId,
      kdfParams: kdfParamsOf(stored.bundle),
      masterKeyByKek: stored.bundle.masterKeyByKek,
      masterKeyByRecovery,
      recoveryKeyByMaster: stored.bundle.recoveryKeyByMaster,
      publicKey: stored.bundle.publicKey,
      privateKeyByMaster: stored.bundle.privateKeyByMaster,
      homeCollection: { id: home.id, keyByOwner: home.keyMaterial, metadataBlob: home.metadataBlob },
    };
  }

  async function recoverLocal(wordsInput: string, newPassword: string): Promise<void> {
    const local = await storedLocalAccount();
    const stored = await requireStored();
    let opened: { masterKey: Uint8Array; recoveryKey: Uint8Array } | null = null;
    try {
      const words = normalizeRecoveryInput(wordsInput);
      opened = openRecovery(sodium, words, local.userId, local.masterKeyByRecovery);
      requirePolicy(newPassword, stored.username);
      const completion = await createRecoveryCompletion(
        sodium,
        { userId: local.userId, newPassword, masterKey: opened.masterKey, recoveryKey: opened.recoveryKey },
        context.kdfStartParams,
      );
      wipe(completion.loginKey);
      wipe(completion.recoveryLoginKey);
      await saveKeyBundle(rowStore, {
        ...stored.bundle,
        salt: completion.salt,
        opsLimit: completion.opsLimit,
        memLimitBytes: completion.memLimitBytes,
        masterKeyByKek: completion.masterKeyByKek,
      });
      await rowStore.kvSetJson(STORAGE_KEYS.accountRecoveryConfirmed, true);
      const accountKey = requireAccountKey();
      await keyVault.storeMasterKey(accountKey, opened.masterKey);
      const privateKey = unwrapPrivateKey(opened.masterKey, local.userId, stored.bundle.privateKeyByMaster);
      unlockedState(stored, { masterKey: opened.masterKey, privateKey, publicKey: stored.bundle.publicKey });
    } catch (error) {
      if (opened !== null) wipe(opened.masterKey);
      throw toAuthError(error);
    } finally {
      if (opened !== null) wipe(opened.recoveryKey);
    }
  }

  async function connectServer(input: {
    readonly serverInput: string;
    readonly inviteToken: string | null;
    readonly username: string;
    readonly password: string;
  }): Promise<void> {
    const username = normalizeUsername(input.username);
    if (!isValidUsername(username)) throw new AuthError('usernameInvalid');
    const accountKey = requireAccountKey();
    const local = await storedLocalAccount();
    const choice = await context.serverUrl.choose(input.serverInput);
    if (!choice.ok) throw new AuthError(choice.reason === 'not_https' ? 'notHttps' : choice.reason === 'unreachable' ? 'unreachable' : 'notKodakiko');
    let request: Awaited<ReturnType<typeof rebuildSignUpRequest>> | null = null;
    try {
      request = await rebuildSignUpRequest(sodium, { password: input.password, username, stored: local });
      const response = await http.request(
        AUTH_CALLS.signUp({
          userId: idSchema.parse(request.userId),
          username: request.username,
          inviteToken: input.inviteToken ?? choice.inviteToken,
          deviceLabel: context.deviceLabel(),
          salt: encodeBytes(request.salt),
          opsLimit: request.opsLimit,
          memLimitBytes: request.memLimitBytes,
          loginKey: encodeBytes(request.loginKey),
          recoveryLoginKey: encodeBytes(request.recoveryLoginKey),
          publicKey: encodeBytes(request.publicKey),
          masterKeyByKek: encodeBytes(request.masterKeyByKek),
          masterKeyByRecovery: encodeBytes(request.masterKeyByRecovery),
          recoveryKeyByMaster: encodeBytes(request.recoveryKeyByMaster),
          privateKeyByMaster: encodeBytes(request.privateKeyByMaster),
          homeCollection: {
            id: idSchema.parse(request.homeCollection.id),
            keyByOwner: encodeBytes(request.homeCollection.keyByOwner),
            metadataBlob: encodeBytes(request.homeCollection.metadataBlob),
          },
        }),
      );
      context.config.accept(choice.config);
      await sessionStore.save(accountKey, response.session.token, true);
      await rowStore.kvSetJson(STORAGE_KEYS.accountMode, 'server');
      await rowStore.kvSetJson(STORAGE_KEYS.accountUsername, username);
      await saveRole(rowStore, response.user.role);
      writeActiveAccount(accountKey, username, 'server');
      const state = accountState.get();
      if (state.kind === 'unlocked') accountState.set({ ...state, username });
    } catch (error) {
      await attempt(() => context.serverUrl.clear?.() ?? Promise.resolve());
      throw toAuthError(error);
    } finally {
      if (request !== null) {
        wipe(request.loginKey);
        wipe(request.recoveryLoginKey);
      }
    }
  }

  async function eraseLocal(): Promise<void> {
    await attempt(() => context.beforeSignOut?.() ?? Promise.resolve());
    const accountKey = context.activeAccountKey.get();
    if (accountKey !== null) {
      await attempt(() => keyVault.forget(accountKey));
      await attempt(() => rowStore.destroy(accountKey));
      await attempt(() => fileStore.destroy(accountKey));
    }
    context.activeAccountKey.set(null);
    await attempt(() => context.globalStore.delete(GLOBAL_STORE_KEYS.activeAccount));
    accountMode.set('server');
    accountState.set({ kind: (await context.serverUrl.current()) === null ? 'noServer' : 'signedOut' });
  }

  async function setKeepUnlocked(enabled: boolean): Promise<void> {
    const vault = keyVault.keepUnlocked;
    const accountKey = context.activeAccountKey.get();
    if (vault === undefined || accountKey === null) return;
    const token = await sessionStore.load(accountKey);
    if (enabled) {
      const state = accountState.get();
      if (state.kind !== 'unlocked') return;
      await vault.enable(accountKey, state.keys.masterKey);
    } else {
      await vault.disable(accountKey);
    }
    if (token !== null) await sessionStore.save(accountKey, token, enabled);
  }

  async function keepUnlocked(): Promise<boolean> {
    const accountKey = context.activeAccountKey.get();
    if (keyVault.keepUnlocked === undefined || accountKey === null) return false;
    return (await keyVault.capabilities(accountKey)).persistent;
  }

  async function signOut(): Promise<void> {
    await attempt(() => context.beforeSignOut?.() ?? Promise.resolve());
    const accountKey = context.activeAccountKey.get();
    await attempt(() => http.request(AUTH_CALLS.signOut()));
    if (accountKey !== null) {
      await attempt(() => sessionStore.clear(accountKey));
      await attempt(() => keyVault.forget(accountKey));
      await attempt(() => rowStore.destroy(accountKey));
      await attempt(() => fileStore.destroy(accountKey));
    }
    context.activeAccountKey.set(null);
    await attempt(() => context.globalStore.delete(GLOBAL_STORE_KEYS.activeAccount));
    context.inviteToken.set(null);
    setSignedOut(null);
  }

  async function recoveryPending(): Promise<boolean> {
    try {
      const confirmed = await rowStore.kvGetJson(
        STORAGE_KEYS.accountRecoveryConfirmed,
        z.boolean(),
      );
      return confirmed === false;
    } catch {
      return true;
    }
  }

  return {
    restore,
    chooseServer,
    signUp,
    confirmRecoveryKeySaved,
    signIn,
    unlockWithPassword,
    unlockWithDevice,
    recover,
    pendingRecoveryWords,
    recoveryPending,
    cancelDeletion,
    signOut,
    setKeepUnlocked,
    keepUnlocked,
    activeUsername: () => readActiveAccount()?.username ?? null,
    changePassword,
    revealRecoveryWords,
    verifyPassword,
    requestAccountDeletion,
    deletionScheduledFor: () => deletionNotice,
    changeServer,
    setUpLocal,
    connectServer,
    eraseLocal,
    mode: accountMode,
  };
}
