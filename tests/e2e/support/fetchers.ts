import { DROPPED_RESPONSE_MESSAGE, OFFLINE_MESSAGE } from './constants.ts';

export type TestFetcher = {
  readonly fetcher: typeof fetch;
  readonly requests: () => readonly string[];
  readonly clearRequests: () => void;
  readonly dropNextResponse: (method: string, count?: number) => void;
  readonly droppedCount: () => number;
  readonly setOffline: (offline: boolean) => void;
};

function urlOf(input: Parameters<typeof fetch>[0]): string {
  if (typeof input === 'string') return input;
  return input instanceof URL ? input.toString() : input.url;
}

function methodOf(input: Parameters<typeof fetch>[0], init: RequestInit | undefined): string {
  if (init?.method !== undefined) return init.method;
  return typeof input === 'object' && 'method' in input ? input.method : 'GET';
}

export function createTestFetcher(): TestFetcher {
  const seen: string[] = [];
  let dropMethod: string | null = null;
  let dropRemaining = 0;
  let dropped = 0;
  let offline = false;
  const fetcher: typeof fetch = async (input, init) => {
    const method = methodOf(input, init);
    seen.push(`${method} ${urlOf(input)}`);
    if (offline) throw new TypeError(OFFLINE_MESSAGE);
    const response = await fetch(input, init);
    if (dropMethod === method && dropRemaining > 0) {
      dropRemaining -= 1;
      if (dropRemaining === 0) dropMethod = null;
      dropped += 1;
      await response.arrayBuffer();
      throw new TypeError(DROPPED_RESPONSE_MESSAGE);
    }
    return response;
  };
  return {
    fetcher,
    requests: () => [...seen],
    clearRequests: () => {
      seen.length = 0;
    },
    dropNextResponse: (method, count = 1) => {
      dropMethod = method;
      dropRemaining = count;
    },
    droppedCount: () => dropped,
    setOffline: (value) => {
      offline = value;
    },
  };
}
