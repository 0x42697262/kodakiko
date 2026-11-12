import sodiumLib from 'libsodium-wrappers-sumo';
import type { Sodium } from '@kodakiko/core';
import { WEB_SODIUM_READY_ATTRIBUTE, WEB_SODIUM_READY_VALUE } from '../../constants/web-constants';
import { WEB_ARGON2_WORKER_PATH } from '../../keys/paths';
import { EN } from '../../text/en';
import { ARGON2_WORKER_ERROR, type Argon2Request, type Argon2Response } from './argon2-messages';
import { createWrappersSodium } from './sodium-wrappers';

type Pending = {
  readonly resolve: (output: Uint8Array) => void;
  readonly reject: (error: Error) => void;
};

function createWorkerArgon2id(): Sodium['argon2id'] {
  let worker: Worker | null = null;
  let nextId = 0;
  const pending = new Map<number, Pending>();
  let queue: Promise<unknown> = Promise.resolve();

  function failAll(error: Error): void {
    for (const entry of pending.values()) entry.reject(error);
    pending.clear();
    worker?.terminate();
    worker = null;
  }

  function ensureWorker(): Worker {
    if (worker !== null) return worker;
    const created = new Worker(WEB_ARGON2_WORKER_PATH);
    created.onmessage = (event: MessageEvent<Argon2Response>) => {
      const entry = pending.get(event.data.id);
      pending.delete(event.data.id);
      if (entry === undefined) return;
      if ('output' in event.data) entry.resolve(event.data.output);
      else entry.reject(new Error(ARGON2_WORKER_ERROR));
    };
    created.onerror = () => failAll(new Error(EN['sodium.workerUnavailable']));
    created.onmessageerror = () => failAll(new Error(EN['sodium.workerUnavailable']));
    worker = created;
    return created;
  }

  function run(input: Parameters<Sodium['argon2id']>[0]): Promise<Uint8Array> {
    return new Promise((resolve, reject) => {
      nextId += 1;
      pending.set(nextId, { resolve, reject });
      const password = input.password.slice();
      const salt = input.salt.slice();
      const request: Argon2Request = {
        id: nextId,
        password,
        salt,
        opsLimit: input.opsLimit,
        memLimitBytes: input.memLimitBytes,
        outputLength: input.outputLength,
      };
      try {
        ensureWorker().postMessage(request, [password.buffer, salt.buffer]);
      } catch (error) {
        pending.delete(nextId);
        reject(error instanceof Error ? error : new Error(EN['sodium.workerUnavailable']));
      }
    });
  }

  return (input) => {
    const result = queue.then(() => run(input));
    queue = result.catch(() => undefined);
    return result;
  };
}

export async function loadSodium(): Promise<Sodium> {
  await sodiumLib.ready;
  document.documentElement.dataset[WEB_SODIUM_READY_ATTRIBUTE] = WEB_SODIUM_READY_VALUE;
  return createWrappersSodium(sodiumLib, createWorkerArgon2id());
}
