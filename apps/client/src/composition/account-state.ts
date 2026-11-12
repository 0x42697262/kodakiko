import type { AccountKeys } from '@kodakiko/core';

export type AccountState =
  | { readonly kind: 'noServer' }
  | { readonly kind: 'signedOut'; readonly username?: string }
  | { readonly kind: 'locked' }
  | {
      readonly kind: 'unlocked';
      readonly keys: AccountKeys;
      readonly userId: string;
      readonly username: string;
    }
  | { readonly kind: 'pendingDeletion' };

export type AccountStateKind = AccountState['kind'];

export type Observable<Value> = {
  readonly get: () => Value;
  readonly set: (value: Value) => void;
  readonly subscribe: (listener: () => void) => () => void;
};

export type AccountStateStore = Observable<AccountState>;

export function createObservable<Value>(initial: Value): Observable<Value> {
  let current = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => current,
    set: (value) => {
      current = value;
      for (const listener of [...listeners]) listener();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

export function createAccountState(initial: AccountState): AccountStateStore {
  return createObservable(initial);
}
