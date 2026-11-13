import { z } from 'zod';
import {
  ROUTES,
  decodeBytes,
  encodeBytes,
  idSchema,
  keyBundleDto,
  paramsRequest,
  recoveryCompleteRequest,
  passwordChangeRequest,
  recoveryStartRequest,
  signInRequest,
  signUpRequest,
  type ErrorCode,
  type KeyBundleDto,
  type UserDto,
} from '@kodakiko/contract';
import {
  ApiError,
  NetworkError,
  type ApiCall,
  type HttpClient,
} from '../../src/adapters/http/http';

const FLOOR_OPS = 32;
const FLOOR_MEM_BYTES = 134_217_728;
const FAKE_SALT_BYTES = 16;
const TOKEN_PREFIX = 'kdk_s_';
const TOKEN_FILL_LENGTH = 43;
const SESSION_LIFETIME_MS = 86_400_000;
const STATUS_UNAUTHORIZED = 401;
const STATUS_FORBIDDEN = 403;
const STATUS_NOT_FOUND = 404;

type StoredUser = {
  id: string;
  username: string;
  role: 'admin' | 'member';
  status: 'active' | 'pending_deletion';
  deletionDueAt: string | null;
  loginKey: string;
  recoveryLoginKey: string;
  bundle: KeyBundleDto;
  masterKeyByRecovery: string;
};

export type RecordedCall = { readonly method: string; readonly path: string };

export type FakeAuthServer = {
  readonly http: (hooks: {
    readonly sessionToken: () => Promise<string | null>;
    readonly onSessionInvalid: () => void;
  }) => HttpClient;
  readonly calls: RecordedCall[];
  online: boolean;
  hostileParams: boolean;
  readonly user: (username: string) => StoredUser;
  readonly replaceBundle: (
    username: string,
    loginKey: Uint8Array | null,
    bundle: KeyBundleDto,
  ) => void;
  readonly revokeSessions: () => void;
  readonly requestDeletion: (username: string) => void;
  readonly markPendingDeletion: (username: string) => void;
  readonly count: (path: string) => number;
  readonly failOnce: (path: string, status: number, code: ErrorCode) => void;
};

function sessionToken(counter: number): string {
  return `${TOKEN_PREFIX}${String(counter).padStart(TOKEN_FILL_LENGTH, 'a')}`;
}

function failure(status: number, code: ErrorCode): ApiError {
  return new ApiError(status, code, null, null);
}

export function createFakeAuthServer(): FakeAuthServer {
  const users = new Map<string, StoredUser>();
  const sessions = new Map<string, string>();
  const calls: RecordedCall[] = [];
  let tokenCounter = 0;

  const state = { online: true, hostileParams: false };
  const plannedFailures = new Map<string, ApiError>();

  function userDto(user: StoredUser): UserDto {
    return {
      id: idSchema.parse(user.id),
      username: user.username,
      role: user.role,
      status: user.status,
      quotaBytes: null,
      usedBytes: 0,
      createdAt: new Date(0).toISOString(),
      deletionDueAt: user.deletionDueAt,
    };
  }

  function issueSession(user: StoredUser, deviceLabel: string) {
    tokenCounter += 1;
    const token = sessionToken(tokenCounter);
    sessions.set(token, user.id);
    return {
      token,
      expiresAt: new Date(SESSION_LIFETIME_MS).toISOString(),
      deviceLabel,
    };
  }

  function byId(userId: string): StoredUser {
    const found = [...users.values()].find((user) => user.id === userId);
    if (found === undefined) throw failure(STATUS_NOT_FOUND, 'NOT_FOUND');
    return found;
  }

  function paramsFor(username: string) {
    const known = users.get(username);
    if (state.hostileParams) {
      return {
        salt: encodeBytes(new Uint8Array(FAKE_SALT_BYTES).fill(1)),
        opsLimit: 1,
        memLimitBytes: 1024,
      };
    }
    if (known !== undefined) {
      return {
        salt: known.bundle.salt,
        opsLimit: known.bundle.opsLimit,
        memLimitBytes: known.bundle.memLimitBytes,
      };
    }
    return {
      salt: encodeBytes(new Uint8Array(FAKE_SALT_BYTES).fill(2)),
      opsLimit: FLOOR_OPS,
      memLimitBytes: FLOOR_MEM_BYTES,
    };
  }

  function sessionUser(token: string | null): StoredUser {
    const userId = token === null ? undefined : sessions.get(token);
    if (userId === undefined) throw failure(STATUS_UNAUTHORIZED, 'SESSION_EXPIRED');
    return byId(userId);
  }

  type Handler = (body: unknown, token: string | null) => unknown;

  const handlers: Record<string, Handler> = {
    [`POST ${ROUTES.authParams()}`]: (body) => paramsFor(paramsRequest.parse(body).username),
    [`POST ${ROUTES.authSignUp()}`]: (body) => {
      const request = signUpRequest.parse(body);
      if (users.has(request.username)) throw failure(409, 'USERNAME_TAKEN');
      const user: StoredUser = {
        id: request.userId,
        username: request.username,
        role: 'member',
        status: 'active',
        deletionDueAt: null,
        loginKey: request.loginKey,
        recoveryLoginKey: request.recoveryLoginKey,
        masterKeyByRecovery: request.masterKeyByRecovery,
        bundle: {
          salt: request.salt,
          opsLimit: request.opsLimit,
          memLimitBytes: request.memLimitBytes,
          masterKeyByKek: request.masterKeyByKek,
          recoveryKeyByMaster: request.recoveryKeyByMaster,
          publicKey: request.publicKey,
          privateKeyByMaster: request.privateKeyByMaster,
        },
      };
      users.set(user.username, user);
      return { session: issueSession(user, request.deviceLabel), user: userDto(user) };
    },
    [`POST ${ROUTES.authSignIn()}`]: (body) => {
      const request = signInRequest.parse(body);
      const user = users.get(request.username);
      if (user === undefined || user.loginKey !== request.loginKey) {
        throw failure(STATUS_UNAUTHORIZED, 'INVALID_CREDENTIALS');
      }
      return {
        session: issueSession(user, request.deviceLabel),
        user: userDto(user),
        keyBundle: user.bundle,
      };
    },
    [`GET ${ROUTES.authSession()}`]: (_body, token) => ({ user: userDto(sessionUser(token)) }),
    [`GET ${ROUTES.authKeyBundle()}`]: (_body, token) => {
      const user = sessionUser(token);
      if (user.status === 'pending_deletion') {
        throw failure(STATUS_FORBIDDEN, 'ACCOUNT_PENDING_DELETION');
      }
      return keyBundleDto.parse(user.bundle);
    },
    [`POST ${ROUTES.authRecoveryStart()}`]: (body) => {
      const request = recoveryStartRequest.parse(body);
      const user = users.get(request.username);
      return user === undefined
        ? {
            userId: encodeBytes(new Uint8Array(FAKE_SALT_BYTES).fill(3)),
            masterKeyByRecovery: encodeBytes(new Uint8Array(73).fill(4)),
          }
        : { userId: user.id, masterKeyByRecovery: user.masterKeyByRecovery };
    },
    [`POST ${ROUTES.authRecoveryComplete()}`]: (body) => {
      const request = recoveryCompleteRequest.parse(body);
      const user = users.get(request.username);
      if (user === undefined || user.recoveryLoginKey !== request.recoveryLoginKey) {
        throw failure(STATUS_UNAUTHORIZED, 'INVALID_CREDENTIALS');
      }
      user.loginKey = request.loginKey;
      user.bundle = {
        ...user.bundle,
        salt: request.salt,
        opsLimit: request.opsLimit,
        memLimitBytes: request.memLimitBytes,
        masterKeyByKek: request.masterKeyByKek,
      };
      sessions.clear();
      return {
        session: issueSession(user, request.deviceLabel),
        user: userDto(user),
        keyBundle: user.bundle,
      };
    },
    [`POST ${ROUTES.authSignOut()}`]: (_body, token) => {
      if (token !== null) sessions.delete(token);
      return undefined;
    },
    [`POST ${ROUTES.authPassword()}`]: (body, token) => {
      const user = sessionUser(token);
      const request = passwordChangeRequest.parse(body);
      if (request.currentLoginKey !== user.loginKey) throw failure(STATUS_UNAUTHORIZED, 'INVALID_CREDENTIALS');
      user.loginKey = request.loginKey;
      user.bundle = {
        ...user.bundle,
        salt: request.salt,
        opsLimit: request.opsLimit,
        memLimitBytes: request.memLimitBytes,
        masterKeyByKek: request.masterKeyByKek,
      };
      sessions.clear();
      return { session: issueSession(user, 'Test Device') };
    },
    [`POST ${ROUTES.usersMeDelete()}`]: (_body, token) => {
      const user = sessionUser(token);
      user.status = 'pending_deletion';
      user.deletionDueAt = new Date(SESSION_LIFETIME_MS).toISOString();
      sessions.clear();
      return { deletionDueAt: user.deletionDueAt };
    },
    [`POST ${ROUTES.usersMeDeleteCancel()}`]: (_body, token) => {
      const user = sessionUser(token);
      user.status = 'active';
      user.deletionDueAt = null;
      return userDto(user);
    },
  };

  function http(hooks: {
    readonly sessionToken: () => Promise<string | null>;
    readonly onSessionInvalid: () => void;
  }): HttpClient {
    return {
      request: async <Response>(call: ApiCall<Response>): Promise<Response> => {
        calls.push({ method: call.method, path: call.path });
        if (!state.online) throw new NetworkError();
        const handler = handlers[`${call.method} ${call.path}`];
        if (handler === undefined) throw failure(STATUS_NOT_FOUND, 'NOT_FOUND');
        const token = call.auth === 'session' ? await hooks.sessionToken() : null;
        try {
          const planned = plannedFailures.get(call.path);
          if (planned !== undefined) {
            plannedFailures.delete(call.path);
            throw planned;
          }
          const raw = handler(call.body, token);
          return call.responseSchema.parse(raw);
        } catch (error) {
          if (error instanceof ApiError && error.status === STATUS_UNAUTHORIZED) {
            if (error.code === 'SESSION_EXPIRED') hooks.onSessionInvalid();
          }
          if (error instanceof z.ZodError) throw failure(500, 'INTERNAL');
          throw error;
        }
      },
    };
  }

  return {
    http,
    calls,
    get online() {
      return state.online;
    },
    set online(value: boolean) {
      state.online = value;
    },
    get hostileParams() {
      return state.hostileParams;
    },
    set hostileParams(value: boolean) {
      state.hostileParams = value;
    },
    user: (username) => {
      const found = users.get(username);
      if (found === undefined) throw new Error(`unknown user ${username}`);
      return found;
    },
    replaceBundle: (username, loginKey, bundle) => {
      const user = users.get(username);
      if (user === undefined) throw new Error(`unknown user ${username}`);
      if (loginKey !== null) user.loginKey = encodeBytes(loginKey);
      user.bundle = bundle;
    },
    revokeSessions: () => sessions.clear(),
    requestDeletion: (username) => {
      const user = users.get(username);
      if (user === undefined) throw new Error(`unknown user ${username}`);
      user.status = 'pending_deletion';
      user.deletionDueAt = new Date(SESSION_LIFETIME_MS).toISOString();
      sessions.clear();
    },
    markPendingDeletion: (username) => {
      const user = users.get(username);
      if (user === undefined) throw new Error(`unknown user ${username}`);
      user.status = 'pending_deletion';
      user.deletionDueAt = new Date(SESSION_LIFETIME_MS).toISOString();
    },
    failOnce: (path, status, code) => {
      plannedFailures.set(path, failure(status, code));
    },
    count: (path) => calls.filter((call) => call.path === path).length,
  };
}

export function bundleBytes(bundle: KeyBundleDto) {
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
