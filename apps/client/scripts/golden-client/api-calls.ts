import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import * as contract from '@kodakiko/contract';
import {
  ROUTES,
  adminInviteCreateRequest,
  adminUserPatchRequest,
  collectionCreateRequest,
  entryCreateRequest,
  entryPatchRequest,
  linkExpirySchema,
  mediaCreateRequest,
  paramsRequest,
  passwordChangeRequest,
  presignRequest,
  publicLinkDto,
  publicLinkListResponse,
  recoveryCompleteRequest,
  recoveryStartRequest,
  signInRequest,
  signUpRequest,
} from '@kodakiko/contract';
import { z } from 'zod';
import type { ApiCall, HttpClient } from '../../src/adapters/http/http';
import type { GlobalStore } from '../../src/adapters/global-store/global-store';
import type { Lifecycle } from '../../src/adapters/lifecycle/lifecycle';
import { createMemoryRowStore } from '../../src/adapters/memory/memory-row-store';
import { createAccountService } from '../../src/services/account/account-service';
import { createAdminClient, type AdminClient } from '../../src/services/admin/admin-service';
import { AUTH_CALLS } from '../../src/services/auth-calls';
import { createConfigService } from '../../src/services/config/config-service';
import { OBJECT_CALLS } from '../../src/services/downloads/object-calls';
import { SHARING_CALLS } from '../../src/services/sharing/sharing-calls';
import { SYNC_CALLS } from '../../src/services/sync/sync-calls';
import { toJson, type GoldenCase, type GoldenWriter, type Json } from './shared';

const PATH = 'api/calls.json';
const FN = 'call';
const CASE_INDEX_SEPARATOR = '#';
const UNKNOWN_SCHEMA = 'unknown';
const VOID_SCHEMA = 'void';
const LINK_SERVICE_SOURCE = new URL('../../src/services/links/public-links-service.ts', import.meta.url);
const LINK_CALLS_BLOCK = /^const LINK_CALLS = Object\.freeze\(\{\n[\s\S]*?\n\}\);$/m;
const LINK_CALLS_SCOPE = ['ROUTES', 'publicLinkDto', 'publicLinkListResponse', 'z'] as const;

type AnyCall = ApiCall<unknown>;
type Builder = (...args: never[]) => AnyCall;
type CallCase = { readonly call: string; readonly args: readonly unknown[] };

const ID = {
  user: 'u7Hq2LmX9pR4sT1vW8yZ0a',
  otherUser: 'Rk3_9xQ2mT7vL0pR8sW4-b',
  collection: 'c0LlEcTiOn_Id-00000001',
  home: 'hOmE_cOlLeCtIoN-00000b',
  entry: 'eNtRy_Id-000000000000a',
  media: 'mEdIa_Id-000000000000b',
  object: 'oBjEcT_Id-00000000000c',
  link: 'lInK_Id-0000000000000d',
  invite: 'iNvItE_Id-00000000000e',
} as const;

const BYTES = {
  salt: 'c2FsdC1ieXRlcy0xNg',
  key: 'a2V5LWJ5dGVzLTMyLWxvbmctZW5vdWdoLXRvLWJlLXJlYWw',
  blob: 'YmxvYi1ieXRlcw',
  sealed: 'c2VhbGVkLWNvbGxlY3Rpb24ta2V5',
  token: 'YWNjZXNzLXRva2Vu',
} as const;

const INVITE_TOKEN = 'kdk_i_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde';
const MAX_SAFE = Number.MAX_SAFE_INTEGER;

function checked<T>(schema: z.ZodType<T>, value: unknown): T {
  return schema.parse(value);
}

const signUpBody = (inviteToken: string | null) =>
  checked(signUpRequest, {
    userId: ID.user,
    username: 'alice',
    inviteToken,
    deviceLabel: 'Pixel 9',
    salt: BYTES.salt,
    opsLimit: 3,
    memLimitBytes: 268435456,
    loginKey: BYTES.key,
    recoveryLoginKey: BYTES.key,
    publicKey: BYTES.key,
    masterKeyByKek: BYTES.blob,
    masterKeyByRecovery: BYTES.blob,
    recoveryKeyByMaster: BYTES.blob,
    privateKeyByMaster: BYTES.blob,
    homeCollection: { id: ID.home, keyByOwner: BYTES.blob, metadataBlob: BYTES.blob },
  });

const AUTH_CASES: readonly CallCase[] = [
  { call: 'params', args: [checked(paramsRequest, { username: 'alice' })] },
  { call: 'params', args: [checked(paramsRequest, { username: 'Ünïcødé User 🌸' })] },
  { call: 'signUp', args: [signUpBody(null)] },
  { call: 'signUp', args: [signUpBody(INVITE_TOKEN)] },
  { call: 'signIn', args: [checked(signInRequest, { username: 'alice', loginKey: BYTES.key, deviceLabel: 'Pixel 9' })] },
  { call: 'session', args: [] },
  { call: 'keyBundle', args: [] },
  { call: 'recoveryStart', args: [checked(recoveryStartRequest, { username: 'alice' })] },
  {
    call: 'recoveryComplete',
    args: [
      checked(recoveryCompleteRequest, {
        username: 'alice',
        recoveryLoginKey: BYTES.key,
        deviceLabel: 'Pixel 9',
        salt: BYTES.salt,
        opsLimit: 2147483647,
        memLimitBytes: MAX_SAFE,
        loginKey: BYTES.key,
        masterKeyByKek: BYTES.blob,
      }),
    ],
  },
  { call: 'signOut', args: [] },
  {
    call: 'changePassword',
    args: [
      checked(passwordChangeRequest, {
        currentLoginKey: BYTES.key,
        salt: BYTES.salt,
        opsLimit: 3,
        memLimitBytes: 268435456,
        loginKey: BYTES.token,
        masterKeyByKek: BYTES.blob,
      }),
    ],
  },
  { call: 'requestDeletion', args: [] },
  { call: 'cancelDeletion', args: [] },
];

const entryBody = (purpose: string, mediaCount: number) =>
  checked(entryCreateRequest, {
    id: ID.entry,
    purpose,
    metadataBlob: BYTES.blob,
    memberships: [{ collectionId: ID.home, entryKeyByCollection: BYTES.sealed }],
    media: Array.from({ length: mediaCount }, (_, index) => ({
      mediaId: `${ID.media.slice(0, -1)}${index}`,
      mediaKeyByEntry: BYTES.key,
    })),
  });

const SYNC_CASES: readonly CallCase[] = [
  { call: 'syncState', args: [] },
  { call: 'collection', args: [ID.home] },
  { call: 'collection', args: [ID.collection] },
  { call: 'diff', args: [ID.home, 0, 500] },
  { call: 'diff', args: [ID.collection, 12345, 1] },
  { call: 'diff', args: [ID.home, MAX_SAFE, 1000] },
  { call: 'createEntry', args: [entryBody('journal', 1)] },
  { call: 'createEntry', args: [entryBody('link_copy', 3)] },
  { call: 'getEntry', args: [ID.entry] },
  { call: 'patchEntry', args: [ID.entry, checked(entryPatchRequest, { metadataBlob: BYTES.blob, baseRevision: 1 })] },
  { call: 'patchEntry', args: [ID.entry, checked(entryPatchRequest, { metadataBlob: BYTES.sealed, baseRevision: 42 })] },
  { call: 'trashEntry', args: [ID.entry] },
  { call: 'restoreEntry', args: [ID.entry] },
  { call: 'deleteEntry', args: [ID.entry] },
  { call: 'addMedia', args: [ID.entry, checked(mediaCreateRequest, { mediaId: ID.media, mediaKeyByEntry: BYTES.key })] },
  { call: 'removeMedia', args: [ID.media] },
  { call: 'presign', args: [checked(presignRequest, { id: ID.object, mediaId: ID.media, variant: 'original', sizeBytes: 5368709120 })] },
  { call: 'presign', args: [checked(presignRequest, { id: ID.object, mediaId: ID.media, variant: 'thumbnail', sizeBytes: 40 })] },
  { call: 'parts', args: [ID.object] },
  { call: 'confirm', args: [ID.object] },
];

const OBJECT_CASES: readonly CallCase[] = [{ call: 'download', args: [ID.object] }];

const SHARING_CASES: readonly CallCase[] = [
  {
    call: 'create',
    args: [checked(collectionCreateRequest, { id: ID.collection, kind: 'shared', keyByOwner: BYTES.key, metadataBlob: BYTES.blob })],
  },
  {
    call: 'create',
    args: [checked(collectionCreateRequest, { id: ID.collection, kind: 'link', keyByOwner: BYTES.key, metadataBlob: BYTES.blob })],
  },
  { call: 'rename', args: [ID.collection, BYTES.blob] },
  { call: 'remove', args: [ID.collection] },
  { call: 'addEntry', args: [ID.collection, ID.entry, BYTES.sealed] },
  { call: 'removeEntry', args: [ID.collection, ID.entry] },
  { call: 'publicKey', args: ['bob'] },
  { call: 'publicKey', args: ['Bob Smith/ä?#'] },
  { call: 'share', args: [ID.collection, ID.otherUser, BYTES.sealed] },
  { call: 'listShares', args: [ID.collection] },
  { call: 'unshare', args: [ID.collection, ID.otherUser] },
];

const CONFIG_CASES: readonly CallCase[] = [{ call: 'get', args: [] }];

const LINK_CASES: readonly CallCase[] = [
  ...linkExpirySchema.options.map((expiry) => ({ call: 'create', args: [ID.collection, BYTES.token, expiry] })),
  { call: 'list', args: [] },
  { call: 'revoke', args: [ID.link] },
];

const ACCOUNT_CASES: readonly CallCase[] = [{ call: 'me', args: [] }];

const ADMIN_CASES: readonly CallCase[] = [
  { call: 'listUsers', args: [null] },
  { call: 'listUsers', args: ['eyJpZCI6IjEyMyJ9'] },
  { call: 'listUsers', args: ['a b/c?d=e&f#g%h+i'] },
  { call: 'listUsers', args: ["-_.!~*'()"] },
  { call: 'listUsers', args: ['äöü €😀 \u00a0\n'] },
  { call: 'patchUser', args: [ID.user, checked(adminUserPatchRequest, { role: 'admin' })] },
  { call: 'patchUser', args: [ID.user, checked(adminUserPatchRequest, { quotaBytes: null })] },
  { call: 'patchUser', args: [ID.otherUser, checked(adminUserPatchRequest, { quotaBytes: 1073741824, role: 'member' })] },
  { call: 'createInvite', args: [checked(adminInviteCreateRequest, { role: 'member', expiresInDays: 7 })] },
  { call: 'createInvite', args: [checked(adminInviteCreateRequest, { role: 'admin', expiresInDays: 1 })] },
  { call: 'listInvites', args: [] },
  { call: 'revokeInvite', args: [ID.invite] },
  { call: 'stats', args: [] },
];

function schemaName(schema: unknown): string {
  const names = Object.entries(contract)
    .filter(([, value]) => value === schema)
    .map(([name]) => name);
  if (names.length === 1 && names[0] !== undefined) return names[0];
  if (names.length > 1) throw new Error(`golden api: schema exported as ${names.join(', ')}`);
  if (schema instanceof z.ZodUnknown) return UNKNOWN_SCHEMA;
  if (schema instanceof z.ZodUndefined) return VOID_SCHEMA;
  throw new Error('golden api: response schema is neither a contract export nor a known local schema');
}

function authJson(auth: AnyCall['auth']): Json {
  if (auth === 'session' || auth === 'none') return auth;
  return { linkToken: auth.linkToken, viewToken: auth.viewToken ?? null };
}

function callJson(call: AnyCall): Json {
  return {
    method: call.method,
    path: call.path,
    auth: authJson(call.auth),
    idempotent: call.idempotent,
    timeoutMs: call.timeoutMs ?? null,
    body: call.body === undefined ? null : toJson(call.body),
    responseSchema: schemaName(call.responseSchema),
  };
}

class CapturedCall extends Error {}

function capturingHttp(captured: AnyCall[]): HttpClient {
  return {
    request: <Response>(call: ApiCall<Response>): Promise<Response> => {
      captured.push(call as AnyCall);
      return Promise.reject(new CapturedCall());
    },
  };
}

async function capture(run: (http: HttpClient) => unknown): Promise<AnyCall> {
  const captured: AnyCall[] = [];
  try {
    await run(capturingHttp(captured));
  } catch (error) {
    if (!(error instanceof CapturedCall)) throw error;
  }
  const [call] = captured;
  if (captured.length !== 1 || call === undefined) throw new Error(`golden api: expected one request, saw ${captured.length}`);
  return call;
}

const NO_GLOBAL_STORE: GlobalStore = { get: () => null, set: () => undefined, delete: async () => undefined };
const NO_LIFECYCLE: Lifecycle = {
  afterInteractions: async () => undefined,
  subscribeActive: () => () => undefined,
  subscribeOnline: () => () => undefined,
};

type AsyncBuilder = (...args: never[]) => Promise<AnyCall>;
type Group = { readonly name: string; readonly builders: Readonly<Record<string, AsyncBuilder>>; readonly cases: readonly CallCase[] };

function sync(builders: object): Readonly<Record<string, AsyncBuilder>> {
  return Object.fromEntries(
    Object.entries(builders as Record<string, Builder>).map(([name, builder]) => [name, async (...args: never[]) => builder(...args)]),
  );
}

function adminBuilders(): Readonly<Record<string, AsyncBuilder>> {
  const names = Object.keys(createAdminClient({ http: capturingHttp([]) })) as (keyof AdminClient)[];
  return Object.fromEntries(
    names.map((name) => [
      name,
      (...args: never[]) => capture((http) => (createAdminClient({ http })[name] as (...input: never[]) => unknown)(...args)),
    ]),
  );
}

async function linkCalls(): Promise<Readonly<Record<string, Builder>>> {
  const source = await readFile(LINK_SERVICE_SOURCE, 'utf8');
  const block = LINK_CALLS_BLOCK.exec(source)?.[0];
  if (block === undefined) throw new Error('golden api: LINK_CALLS not found in public-links-service.ts');
  const code = stripTypeScriptTypes(block);
  const build = new Function(...LINK_CALLS_SCOPE, `${code}\nreturn LINK_CALLS;`) as (...scope: unknown[]) => Record<string, Builder>;
  return build(ROUTES, publicLinkDto, publicLinkListResponse, z);
}

async function groups(): Promise<readonly Group[]> {
  const rowStore = createMemoryRowStore();
  return [
    { name: 'AUTH', builders: sync(AUTH_CALLS), cases: AUTH_CASES },
    { name: 'SYNC', builders: sync(SYNC_CALLS), cases: SYNC_CASES },
    { name: 'OBJECT', builders: sync(OBJECT_CALLS), cases: OBJECT_CASES },
    { name: 'SHARING', builders: sync(SHARING_CALLS), cases: SHARING_CASES },
    {
      name: 'CONFIG',
      builders: { get: () => capture((http) => createConfigService({ http, globalStore: NO_GLOBAL_STORE, lifecycle: NO_LIFECYCLE })) },
      cases: CONFIG_CASES,
    },
    { name: 'LINK', builders: sync(await linkCalls()), cases: LINK_CASES },
    { name: 'ACCOUNT', builders: { me: () => capture((http) => createAccountService({ http, rowStore }).refreshMe()) }, cases: ACCOUNT_CASES },
    { name: 'ADMIN', builders: adminBuilders(), cases: ADMIN_CASES },
  ];
}

export async function writeApiCallGoldens(writer: GoldenWriter): Promise<void> {
  const cases: GoldenCase[] = [];
  for (const group of await groups()) {
    const untested = Object.keys(group.builders).filter((call) => !group.cases.some((item) => item.call === call));
    if (untested.length > 0) throw new Error(`golden api: ${group.name} has no case for ${untested.join(', ')}`);
    const counters = new Map<string, number>();
    for (const item of group.cases) {
      const builder = group.builders[item.call];
      if (builder === undefined) throw new Error(`golden api: ${group.name}.${item.call} does not exist`);
      const index = (counters.get(item.call) ?? 0) + 1;
      counters.set(item.call, index);
      cases.push({
        name: `${group.name}.${item.call}${CASE_INDEX_SEPARATOR}${index}`,
        fn: FN,
        args: { group: group.name, call: item.call, args: item.args.map(toJson) },
        result: callJson(await builder(...(item.args as never[]))),
      });
    }
  }
  await writer.functionCases(PATH, cases);
}
