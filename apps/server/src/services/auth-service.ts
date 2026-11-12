import { SESSION_TOKEN_REGEX, SESSION_TOKEN_PREFIX, INVITE_TOKEN_PREFIX, TOKEN_RANDOM_BYTES } from '@kodakiko/contract';
import {
  SALT_BYTES,
  isValidUsername,
  normalizeUsername,
  startKdfParams,
} from '@kodakiko/core';
import type { Kysely } from 'kysely';
import { createCollectionsRepository } from '../adapters/db/collections-repository.ts';
import { createInvitesRepository } from '../adapters/db/invites-repository.ts';
import type { Database } from '../adapters/db/schema.ts';
import { createSessionsRepository, type NewSession } from '../adapters/db/sessions-repository.ts';
import { createUsersRepository, type UserRow } from '../adapters/db/users-repository.ts';
import type { FailedSignInTracker } from '../adapters/memory/failed-sign-in-tracker.ts';
import {
  BEARER_PREFIX,
  DAY_MS,
  DUMMY_LOGIN_HASH_SEED,
  INVITE_FRAGMENT_PREFIX,
  INVITE_PATH,
  LOGIN_KEY_BYTES,
  PUBLIC_KEY_BYTES,
  USERNAME_LOG_HASH_HEX_CHARS,
  WRAPPED_KEY_BYTES,
} from '../config/constants.ts';
import type { Settings } from '../config/settings.ts';
import {
  failedSignInDelayMs,
  kdfParamsAcceptable,
  kdfWorkWithinBound,
  sessionExpiry,
  shouldTouchSession,
  signUpAllowed,
} from '../domain/auth-rules.ts';
import { AppError } from '../domain/errors.ts';
import type { UserRole, UserStatus } from '../domain/enums.ts';
import type { Clock } from '../platform/clock.ts';
import { constantTimeEqual, sha256, sha256OfText } from '../platform/hash.ts';
import { randomId, randomToken } from '../platform/ids.ts';
import type { AuthLogger } from '../platform/silent-logger.ts';
import { LOG_EVENTS } from '../text/events.ts';
import { fakeKdfSalt, fakeRecoveryWrap, fakeUserId } from './fake-auth-material.ts';

const DUMMY_LOGIN_HASH = sha256OfText(DUMMY_LOGIN_HASH_SEED);

export type KdfNumbers = {
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
};

export type SignUpCommand = KdfNumbers & {
  readonly userId: string;
  readonly username: string;
  readonly inviteToken: string | null;
  readonly loginKey: Uint8Array;
  readonly recoveryLoginKey: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
  readonly masterKeyByRecovery: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
  readonly homeCollection: {
    readonly id: string;
    readonly keyByOwner: Uint8Array;
    readonly metadataBlob: Uint8Array;
  };
};

export type RecoveryCompleteCommand = KdfNumbers & {
  readonly username: string;
  readonly recoveryLoginKey: Uint8Array;
  readonly loginKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
};

export type PasswordChangeCommand = KdfNumbers & {
  readonly currentLoginKey: Uint8Array;
  readonly loginKey: Uint8Array;
  readonly masterKeyByKek: Uint8Array;
};

export type UserView = {
  readonly id: string;
  readonly username: string;
  readonly role: UserRole;
  readonly status: UserStatus;
  readonly quotaBytes: number | null;
  readonly usedBytes: number;
  readonly createdAt: Date;
  readonly deletionDueAt: Date | null;
};

export type SessionView = {
  readonly token: string;
  readonly expiresAt: Date;
  readonly deviceLabel: string;
};

export type KeyBundleView = KdfNumbers & {
  readonly masterKeyByKek: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
};

export type SignedInView = {
  readonly session: SessionView;
  readonly user: UserView;
  readonly keyBundle: KeyBundleView;
};

export type AuthContext = {
  readonly userId: string;
  readonly sessionId: string;
  readonly role: UserRole;
  readonly status: UserStatus;
  readonly deviceLabel: string;
};

export type AuthServiceDeps = {
  readonly db: Kysely<Database>;
  readonly settings: Settings;
  readonly clock: Clock;
  readonly failedSignIns: FailedSignInTracker;
  readonly logger: AuthLogger;
  readonly checkOpenSignUpRate: (ip: string) => Promise<boolean>;
};

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function hasLength(value: Uint8Array, length: number): boolean {
  return value.length === length;
}

function userViewOf(row: UserRow): UserView {
  return {
    id: row.id,
    username: row.username,
    role: row.role,
    status: row.status,
    quotaBytes: row.quotaBytes,
    usedBytes: row.usedBytes,
    createdAt: row.createdAt,
    deletionDueAt: row.deletionDueAt,
  };
}

function keyBundleViewOf(row: UserRow): KeyBundleView {
  return {
    salt: row.kdfSalt,
    opsLimit: row.kdfOpsLimit,
    memLimitBytes: row.kdfMemLimitBytes,
    masterKeyByKek: row.masterKeyByKek,
    recoveryKeyByMaster: row.recoveryKeyByMaster,
    publicKey: row.publicKey,
    privateKeyByMaster: row.privateKeyByMaster,
  };
}

function loginKeyMatches(stored: Buffer | undefined, provided: Uint8Array): boolean {
  const matches = constantTimeEqual(sha256(provided), stored ?? DUMMY_LOGIN_HASH);
  return matches && stored !== undefined;
}

function requireBoundedWork(numbers: KdfNumbers): void {
  if (!kdfWorkWithinBound(numbers)) throw new AppError('VALIDATION_FAILED');
}

function acceptableNumbers(numbers: KdfNumbers): boolean {
  return kdfParamsAcceptable({
    saltLength: numbers.salt.length,
    opsLimit: numbers.opsLimit,
    memLimitBytes: numbers.memLimitBytes,
  });
}

export function createAuthService(deps: AuthServiceDeps) {
  const { db, settings, clock, failedSignIns, logger } = deps;
  const { auth } = settings;

  function newSession(
    userId: string,
    deviceLabel: string,
    now: Date,
  ): { readonly row: NewSession; readonly view: SessionView } {
    const token = `${SESSION_TOKEN_PREFIX}${randomToken(TOKEN_RANDOM_BYTES)}`;
    const expiresAt = sessionExpiry(now, auth.sessionIdleDays);
    return {
      row: {
        id: randomId(),
        userId,
        tokenHash: sha256OfText(token),
        deviceLabel,
        createdAt: now,
        lastUsedAt: now,
        expiresAt,
      },
      view: { token, expiresAt, deviceLabel },
    };
  }

  function usernameHash(normalizedUsername: string): string {
    return sha256OfText(normalizedUsername).toString('hex');
  }

  async function applyFailureDelay(key: string): Promise<void> {
    const delay = failedSignInDelayMs(
      failedSignIns.failureCount(key, clock.now()),
      auth.maxFailedSignIns,
    );
    if (delay === 0) return;
    logger.warn({
      event: LOG_EVENTS.authThrottled,
      usernameHash: key.slice(0, USERNAME_LOG_HASH_HEX_CHARS),
      delayMs: delay,
    });
    await sleep(delay);
  }

  function requireLengths(fields: readonly (readonly [Uint8Array, number])[]): void {
    if (fields.some(([value, length]) => !hasLength(value, length))) {
      throw new AppError('VALIDATION_FAILED');
    }
  }

  function requireLoginMaterialShape(command: {
    readonly loginKey: Uint8Array;
    readonly masterKeyByKek: Uint8Array;
    readonly salt: Uint8Array;
  }): void {
    requireLengths([
      [command.loginKey, LOGIN_KEY_BYTES],
      [command.masterKeyByKek, WRAPPED_KEY_BYTES],
      [command.salt, SALT_BYTES],
    ]);
  }

  async function reloadUser(userId: string): Promise<UserRow> {
    const row = await createUsersRepository(db).findById(userId);
    if (row === undefined) throw new AppError('NOT_FOUND');
    return row;
  }

  async function params(username: string): Promise<KdfNumbers> {
    const normalized = normalizeUsername(username);
    const fake = (): KdfNumbers =>
      startKdfParams(fakeKdfSalt(auth.fakeParamsSecret, normalized));
    if (!isValidUsername(normalized)) return fake();
    const user = await createUsersRepository(db).findByUsername(normalized);
    if (user === undefined) return fake();
    return {
      salt: user.kdfSalt,
      opsLimit: user.kdfOpsLimit,
      memLimitBytes: user.kdfMemLimitBytes,
    };
  }

  async function signUp(
    request: SignUpCommand,
    deviceLabel: string,
    ip: string,
  ): Promise<{ readonly session: SessionView; readonly user: UserView }> {
    if (settings.flags.signupMode === 'open' && !(await deps.checkOpenSignUpRate(ip))) {
      throw new AppError('RATE_LIMITED');
    }
    if (
      normalizeUsername(request.username) !== request.username ||
      !isValidUsername(request.username)
    ) {
      throw new AppError('VALIDATION_FAILED');
    }
    requireLengths([
      [request.loginKey, LOGIN_KEY_BYTES],
      [request.recoveryLoginKey, LOGIN_KEY_BYTES],
      [request.publicKey, PUBLIC_KEY_BYTES],
      [request.salt, SALT_BYTES],
      [request.masterKeyByKek, WRAPPED_KEY_BYTES],
      [request.masterKeyByRecovery, WRAPPED_KEY_BYTES],
      [request.recoveryKeyByMaster, WRAPPED_KEY_BYTES],
      [request.privateKeyByMaster, WRAPPED_KEY_BYTES],
      [request.homeCollection.keyByOwner, WRAPPED_KEY_BYTES],
    ]);
    if (request.homeCollection.metadataBlob.length > settings.limits.maxCollectionMetadataBytes) {
      throw new AppError('VALIDATION_FAILED');
    }
    requireBoundedWork(request);
    if (!acceptableNumbers(request)) throw new AppError('KDF_PARAMS_TOO_WEAK');

    const now = clock.now();
    const result = await db.transaction().execute(async (trx) => {
      const invites = createInvitesRepository(trx);
      const invite =
        request.inviteToken === null
          ? undefined
          : await invites.findByTokenHashForUpdate(sha256OfText(request.inviteToken));
      const decision = signUpAllowed(settings.flags.signupMode, invite ?? null, now);
      if (!decision.allowed) throw new AppError('INVITE_INVALID');

      const outcome = await createUsersRepository(trx).insert({
        id: request.userId,
        username: request.username,
        role: decision.role,
        status: 'active',
        loginKeyHash: sha256(request.loginKey),
        recoveryLoginKeyHash: sha256(request.recoveryLoginKey),
        kdfSalt: Buffer.from(request.salt),
        kdfOpsLimit: request.opsLimit,
        kdfMemLimitBytes: request.memLimitBytes,
        masterKeyByKek: Buffer.from(request.masterKeyByKek),
        masterKeyByRecovery: Buffer.from(request.masterKeyByRecovery),
        recoveryKeyByMaster: Buffer.from(request.recoveryKeyByMaster),
        publicKey: Buffer.from(request.publicKey),
        privateKeyByMaster: Buffer.from(request.privateKeyByMaster),
        quotaBytes: null,
        deletionDueAt: null,
        createdAt: now,
        updatedAt: now,
      });
      if (outcome === 'usernameTaken') throw new AppError('USERNAME_TAKEN');
      if (outcome === 'idConflict') throw new AppError('ID_CONFLICT');

      const homeOutcome = await createCollectionsRepository(trx).insertHome({
        id: request.homeCollection.id,
        ownerId: request.userId,
        keyByOwner: Buffer.from(request.homeCollection.keyByOwner),
        metadataBlob: Buffer.from(request.homeCollection.metadataBlob),
        now,
      });
      if (homeOutcome === 'idConflict') throw new AppError('ID_CONFLICT');
      if (invite !== undefined && settings.flags.signupMode === 'invite') {
        await invites.markRedeemed(invite.id, request.userId, now);
      }
      const session = newSession(request.userId, deviceLabel, now);
      await createSessionsRepository(trx).insert(session.row);
      return { session: session.view, role: decision.role };
    });
    logger.info({ event: LOG_EVENTS.authSignedUp, userId: request.userId, role: result.role });
    return { session: result.session, user: userViewOf(await reloadUser(request.userId)) };
  }

  async function signIn(
    username: string,
    loginKey: Uint8Array,
    deviceLabel: string,
  ): Promise<SignedInView> {
    const normalized = normalizeUsername(username);
    const key = usernameHash(normalized);
    await applyFailureDelay(key);
    const user = await createUsersRepository(db).findByUsername(normalized);
    if (!loginKeyMatches(user?.loginKeyHash, loginKey) || user === undefined) {
      failedSignIns.recordFailure(key, clock.now());
      logger.warn({
        event: LOG_EVENTS.authSignInFailed,
        usernameHash: key.slice(0, USERNAME_LOG_HASH_HEX_CHARS),
      });
      throw new AppError('INVALID_CREDENTIALS');
    }
    failedSignIns.clear(key);
    const session = newSession(user.id, deviceLabel, clock.now());
    await createSessionsRepository(db).insert(session.row);
    return { session: session.view, user: userViewOf(user), keyBundle: keyBundleViewOf(user) };
  }

  async function keyBundle(userId: string): Promise<KeyBundleView> {
    return keyBundleViewOf(await reloadUser(userId));
  }

  async function currentUser(userId: string): Promise<UserView> {
    return userViewOf(await reloadUser(userId));
  }

  async function recoveryStart(
    username: string,
  ): Promise<{ readonly userId: string; readonly masterKeyByRecovery: Uint8Array }> {
    const normalized = normalizeUsername(username);
    const user = await createUsersRepository(db).findByUsername(normalized);
    if (user !== undefined) {
      return { userId: user.id, masterKeyByRecovery: user.masterKeyByRecovery };
    }
    return {
      userId: fakeUserId(auth.fakeParamsSecret, normalized),
      masterKeyByRecovery: fakeRecoveryWrap(auth.fakeParamsSecret, normalized),
    };
  }

  function recoveryFailure(key: string): AppError {
    failedSignIns.recordFailure(key, clock.now());
    logger.warn({
      event: LOG_EVENTS.authRecoveryFailed,
      usernameHash: key.slice(0, USERNAME_LOG_HASH_HEX_CHARS),
    });
    return new AppError('INVALID_CREDENTIALS');
  }

  async function recoveryComplete(
    request: RecoveryCompleteCommand,
    deviceLabel: string,
  ): Promise<SignedInView> {
    requireLoginMaterialShape(request);
    requireBoundedWork(request);
    const normalized = normalizeUsername(request.username);
    const key = usernameHash(normalized);
    await applyFailureDelay(key);
    const user = await createUsersRepository(db).findByUsername(normalized);
    if (!loginKeyMatches(user?.recoveryLoginKeyHash, request.recoveryLoginKey) || user === undefined) {
      throw recoveryFailure(key);
    }
    if (!acceptableNumbers(request)) throw new AppError('KDF_PARAMS_TOO_WEAK');

    const now = clock.now();
    const session = newSession(user.id, deviceLabel, now);
    await db
      .transaction()
      .execute(async (trx) => {
        const locked = await createUsersRepository(trx).findByUsernameForUpdate(normalized);
        if (
          locked === undefined ||
          !loginKeyMatches(locked.recoveryLoginKeyHash, request.recoveryLoginKey)
        ) {
          throw new AppError('INVALID_CREDENTIALS');
        }
        await createUsersRepository(trx).updateLoginMaterial(locked.id, {
          kdfSalt: Buffer.from(request.salt),
          kdfOpsLimit: request.opsLimit,
          kdfMemLimitBytes: request.memLimitBytes,
          loginKeyHash: sha256(request.loginKey),
          masterKeyByKek: Buffer.from(request.masterKeyByKek),
          updatedAt: now,
        });
        const sessions = createSessionsRepository(trx);
        await sessions.deleteAllForUser(locked.id);
        await sessions.insert(session.row);
      })
      .catch((error: unknown) => {
        if (error instanceof AppError && error.code === 'INVALID_CREDENTIALS') {
          throw recoveryFailure(key);
        }
        throw error;
      });
    failedSignIns.clear(key);
    logger.info({ event: LOG_EVENTS.authRecovered, userId: user.id });
    const updated = await reloadUser(user.id);
    return { session: session.view, user: userViewOf(updated), keyBundle: keyBundleViewOf(updated) };
  }

  async function changePassword(
    userId: string,
    sessionId: string,
    deviceLabel: string,
    request: PasswordChangeCommand,
  ): Promise<SessionView> {
    requireLoginMaterialShape(request);
    requireBoundedWork(request);
    const now = clock.now();
    const session = newSession(userId, deviceLabel, now);
    await db.transaction().execute(async (trx) => {
      const locked = await createUsersRepository(trx).findByIdForUpdate(userId);
      if (locked === undefined || !loginKeyMatches(locked.loginKeyHash, request.currentLoginKey)) {
        throw new AppError('INVALID_CREDENTIALS');
      }
      if (!acceptableNumbers(request)) throw new AppError('KDF_PARAMS_TOO_WEAK');
      const sessions = createSessionsRepository(trx);
      if ((await sessions.deleteById(sessionId)) === 0) throw new AppError('UNAUTHENTICATED');
      await createUsersRepository(trx).updateLoginMaterial(userId, {
        kdfSalt: Buffer.from(request.salt),
        kdfOpsLimit: request.opsLimit,
        kdfMemLimitBytes: request.memLimitBytes,
        loginKeyHash: sha256(request.loginKey),
        masterKeyByKek: Buffer.from(request.masterKeyByKek),
        updatedAt: now,
      });
      await sessions.deleteAllForUser(userId);
      await sessions.insert(session.row);
    });
    logger.info({ event: LOG_EVENTS.authPasswordChanged, userId });
    return session.view;
  }

  async function signOut(sessionId: string): Promise<void> {
    await createSessionsRepository(db).deleteById(sessionId);
  }

  async function authenticate(
    authorizationHeader: string | undefined,
    now: Date,
  ): Promise<AuthContext> {
    if (authorizationHeader === undefined || !authorizationHeader.startsWith(BEARER_PREFIX)) {
      throw new AppError('UNAUTHENTICATED');
    }
    const token = authorizationHeader.slice(BEARER_PREFIX.length);
    if (!SESSION_TOKEN_REGEX.test(token)) throw new AppError('UNAUTHENTICATED');
    const sessions = createSessionsRepository(db);
    const session = await sessions.findByTokenHash(sha256OfText(token));
    if (session === undefined) throw new AppError('UNAUTHENTICATED');
    if (session.expiresAt.getTime() <= now.getTime()) {
      await sessions.deleteById(session.id);
      throw new AppError('SESSION_EXPIRED');
    }
    if (shouldTouchSession(session.lastUsedAt, now)) {
      await sessions.touch(session.id, now, sessionExpiry(now, auth.sessionIdleDays));
    }
    return {
      userId: session.userId,
      sessionId: session.id,
      role: session.userRole,
      status: session.userStatus,
      deviceLabel: session.deviceLabel,
    };
  }

  async function requestDeletion(userId: string): Promise<{ readonly deletionDueAt: Date }> {
    const now = clock.now();
    const deletionDueAt = new Date(now.getTime() + settings.accounts.deletionGraceDays * DAY_MS);
    await db.transaction().execute(async (trx) => {
      await createUsersRepository(trx).setStatus(userId, 'pending_deletion', deletionDueAt, now);
      await createSessionsRepository(trx).deleteAllForUser(userId);
    });
    logger.info({ event: LOG_EVENTS.accountDeletionRequested, userId });
    return { deletionDueAt };
  }

  async function cancelDeletion(userId: string): Promise<UserView> {
    const now = clock.now();
    await db.transaction().execute(async (trx) => {
      const users = createUsersRepository(trx);
      const locked = await users.findByIdForUpdate(userId);
      if (locked === undefined) throw new AppError('FORBIDDEN');
      if ((await users.cancelPendingDeletion(userId, now)) === 0) throw new AppError('FORBIDDEN');
    });
    logger.info({ event: LOG_EVENTS.accountDeletionCancelled, userId });
    return userViewOf(await reloadUser(userId));
  }

  async function createInvite(
    role: UserRole,
    expiresInDays: number,
    createdBy: string | null,
  ): Promise<{ readonly inviteUrl: string; readonly expiresAt: Date }> {
    const now = clock.now();
    const token = `${INVITE_TOKEN_PREFIX}${randomToken(TOKEN_RANDOM_BYTES)}`;
    const expiresAt = new Date(now.getTime() + expiresInDays * DAY_MS);
    await createInvitesRepository(db).insert({
      id: randomId(),
      tokenHash: sha256OfText(token),
      createdBy,
      role,
      expiresAt,
      redeemedBy: null,
      redeemedAt: null,
      revokedAt: null,
      createdAt: now,
    });
    const inviteUrl = `${settings.server.publicBaseUrl}${INVITE_PATH}${INVITE_FRAGMENT_PREFIX}${token}`;
    return { inviteUrl, expiresAt };
  }

  return {
    params,
    signUp,
    signIn,
    keyBundle,
    currentUser,
    recoveryStart,
    recoveryComplete,
    changePassword,
    signOut,
    authenticate,
    requestDeletion,
    cancelDeletion,
    createInvite,
  };
}

export type AuthService = ReturnType<typeof createAuthService>;
