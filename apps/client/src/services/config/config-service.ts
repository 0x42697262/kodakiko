import { ROUTES, publicConfigSchema, type PublicConfigDto } from '@kodakiko/contract';
import type { GlobalStore } from '../../adapters/global-store/global-store';
import type { HttpClient } from '../../adapters/http/http';
import type { Lifecycle } from '../../adapters/lifecycle/lifecycle';
import { GLOBAL_STORE_KEYS } from '../../keys/auth-keys';

export type ConfigService = {
  readonly get: () => PublicConfigDto | null;
  readonly refresh: () => Promise<PublicConfigDto | null>;
  readonly accept: (config: PublicConfigDto) => void;
  readonly subscribe: (listener: (config: PublicConfigDto) => void) => () => void;
};

export type ConfigServiceDeps = {
  readonly http: HttpClient;
  readonly globalStore: GlobalStore;
  readonly lifecycle: Lifecycle;
};

export function storeServerConfig(globalStore: GlobalStore, config: PublicConfigDto): void {
  globalStore.set(GLOBAL_STORE_KEYS.serverConfig, JSON.stringify(config));
}

function loadStoredConfig(globalStore: GlobalStore): PublicConfigDto | null {
  const stored = globalStore.get(GLOBAL_STORE_KEYS.serverConfig);
  if (stored === null) return null;
  try {
    const parsed = publicConfigSchema.safeParse(JSON.parse(stored));
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

export function createConfigService(deps: ConfigServiceDeps): ConfigService {
  let current = loadStoredConfig(deps.globalStore);
  const listeners = new Set<(config: PublicConfigDto) => void>();

  function accept(config: PublicConfigDto): void {
    current = config;
    storeServerConfig(deps.globalStore, config);
    for (const listener of [...listeners]) listener(config);
  }

  async function refresh(): Promise<PublicConfigDto | null> {
    try {
      const fresh = await deps.http.request({
        method: 'GET',
        path: ROUTES.config(),
        responseSchema: publicConfigSchema,
        auth: 'none',
        idempotent: true,
      });
      accept(fresh);
    } catch {
      return current;
    }
    return current;
  }

  refresh().catch(() => undefined);
  deps.lifecycle.subscribeActive((active) => {
    if (active) refresh().catch(() => undefined);
  });

  return {
    get: () => current,
    refresh,
    accept,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
