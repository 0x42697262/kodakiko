export type TaskQueue = { readonly run: <Result>(task: () => Promise<Result>) => Promise<Result> };

export function createTaskQueue(concurrency: number): TaskQueue {
  let active = 0;
  const waiting: (() => void)[] = [];

  function release(): void {
    active -= 1;
    waiting.shift()?.();
  }

  async function run<Result>(task: () => Promise<Result>): Promise<Result> {
    if (active >= concurrency) await new Promise<void>((resolve) => waiting.push(resolve));
    active += 1;
    try {
      return await task();
    } finally {
      release();
    }
  }

  return { run };
}
