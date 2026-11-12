import { toPushFailure } from '../push-failure';
import type { Executor } from './executor-types';

export function guarded(executor: Executor): Executor {
  return async (item, deps) => {
    try {
      return await executor(item, deps);
    } catch (error) {
      const failure = toPushFailure(error);
      if (failure === null) throw error;
      return { failure };
    }
  };
}
