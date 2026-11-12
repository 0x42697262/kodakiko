import { ROUTES, publicConfigSchema, type PublicConfigDto } from '@kodakiko/contract';
import { LOCAL_HTTP_HOSTS } from '../../constants/server-url-constants';
import {
  DEFAULT_SCHEME,
  FRAGMENT_PREFIX,
  HTTPS_PROTOCOL,
  HTTP_PROTOCOL,
  INVITE_PATH,
  SCHEME_PATTERN,
} from '../../keys/server-url-keys';
import { EN } from '../../text/en';

export type ServerChoice =
  | {
      readonly ok: true;
      readonly baseUrl: string;
      readonly config: PublicConfigDto;
      readonly inviteToken: string | null;
    }
  | {
      readonly ok: false;
      readonly reason: 'invalid' | 'not_https' | 'unreachable' | 'not_kodakiko';
    };

export interface ServerUrlStore {
  readonly current: () => Promise<string | null>;
  readonly choose: (input: string) => Promise<ServerChoice>;
}

export type ServerUrlStorage = {
  readonly get: () => Promise<string | null>;
  readonly set: (value: string) => Promise<void>;
};

export type ServerUrlDeps = {
  readonly fetcher: typeof fetch;
  readonly storage: ServerUrlStorage;
  readonly timeoutMs: number;
  readonly allowLocalHttp: boolean;
};

type ParsedInput =
  | { readonly ok: true; readonly url: URL }
  | { readonly ok: false; readonly reason: 'invalid' | 'not_https' };

function parseInput(input: string, allowLocalHttp: boolean): ParsedInput {
  const trimmed = input.trim();
  if (trimmed === '') return { ok: false, reason: 'invalid' };
  const candidate = SCHEME_PATTERN.test(trimmed) ? trimmed : `${DEFAULT_SCHEME}${trimmed}`;
  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    return { ok: false, reason: 'invalid' };
  }
  if (url.protocol === HTTP_PROTOCOL) {
    const allowed = allowLocalHttp && LOCAL_HTTP_HOSTS.includes(url.hostname);
    return allowed ? { ok: true, url } : { ok: false, reason: 'not_https' };
  }
  return url.protocol === HTTPS_PROTOCOL ? { ok: true, url } : { ok: false, reason: 'invalid' };
}

function inviteTokenOf(url: URL): string | null {
  if (url.pathname !== INVITE_PATH) return null;
  const token = url.hash.startsWith(FRAGMENT_PREFIX) ? url.hash.slice(FRAGMENT_PREFIX.length) : '';
  return token === '' ? null : token;
}

async function fetchConfig(
  deps: ServerUrlDeps,
  origin: string,
): Promise<PublicConfigDto | 'unreachable' | 'not_kodakiko'> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), deps.timeoutMs);
  try {
    const response = await deps.fetcher(`${origin}${ROUTES.config()}`, {
      method: 'GET',
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) return 'not_kodakiko';
    const parsed = publicConfigSchema.safeParse(JSON.parse(text));
    return parsed.success ? parsed.data : 'not_kodakiko';
  } catch (error) {
    return error instanceof SyntaxError ? 'not_kodakiko' : 'unreachable';
  } finally {
    clearTimeout(timer);
  }
}

export function createServerUrlStore(): ServerUrlStore;
export function createServerUrlStore(deps: ServerUrlDeps): ServerUrlStore;
export function createServerUrlStore(deps?: ServerUrlDeps): ServerUrlStore {
  if (deps === undefined) throw new Error(EN['serverUrl.needsPlatformWiring']);
  const resolved: ServerUrlDeps = deps;
  async function choose(input: string): Promise<ServerChoice> {
    const parsed = parseInput(input, resolved.allowLocalHttp);
    if (!parsed.ok) return parsed;
    const { origin } = parsed.url;
    const config = await fetchConfig(resolved, origin);
    if (typeof config === 'string') return { ok: false, reason: config };
    await resolved.storage.set(origin);
    return { ok: true, baseUrl: origin, config, inviteToken: inviteTokenOf(parsed.url) };
  }
  return { current: () => resolved.storage.get(), choose };
}
