import { mkdirSync, mkdtempSync, existsSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanAndroid } from '../scripts/clean-android-lib.ts';

let root = '';

function file(path: string, bytes: number): void {
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, new Uint8Array(bytes));
}

function link(target: string, path: string): void {
  mkdirSync(join(path, '..'), { recursive: true });
  symlinkSync(target, path, 'dir');
}

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), 'kodakiko-clean-'));
});

afterEach(() => {
  rmSync(root, { recursive: true, force: true });
});

describe('clean android', () => {
  it('deletes the project build directories and dependency intermediates and reports the bytes', async () => {
    const client = join(root, 'client');
    const store = join(root, 'store');
    file(join(client, 'android/app/build/out.bin'), 1000);
    file(join(client, 'android/build/out.bin'), 500);
    file(join(client, 'android/app/src/main/keep.kt'), 10);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/android/build/a.o'), 200);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/android/.cxx/b.o'), 300);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/android/src/keep.kt'), 10);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/ios/build/keep.o'), 10);
    file(join(store, 'pkg-t@1/node_modules/pkg-t/android/.cxx/t.o'), 70);
    file(join(store, 'scoped@1/node_modules/@scope/pkg-b/android/build/c.o'), 50);
    link(join(store, 'pkg-a@1/node_modules/pkg-a'), join(client, 'node_modules/pkg-a'));
    link(
      join(store, 'scoped@1/node_modules/@scope/pkg-b'),
      join(client, 'node_modules/@scope/pkg-b'),
    );
    link(join(store, 'pkg-t@1/node_modules/pkg-t'), join(store, 'pkg-a@1/node_modules/pkg-t'));

    const result = await cleanAndroid(client);

    expect(result.bytesFreed).toBe(1000 + 500 + 200 + 300 + 70 + 50);
    expect(existsSync(join(client, 'android/app/build'))).toBe(false);
    expect(existsSync(join(client, 'android/build'))).toBe(false);
    expect(existsSync(join(store, 'pkg-a@1/node_modules/pkg-a/android/build'))).toBe(false);
    expect(existsSync(join(store, 'pkg-a@1/node_modules/pkg-a/android/.cxx'))).toBe(false);
    expect(existsSync(join(store, 'pkg-t@1/node_modules/pkg-t/android/.cxx'))).toBe(false);
    expect(existsSync(join(store, 'scoped@1/node_modules/@scope/pkg-b/android/build'))).toBe(false);
  });

  it('keeps sources, other platforms and the dependency links', async () => {
    const client = join(root, 'client');
    const store = join(root, 'store');
    file(join(client, 'android/app/src/main/keep.kt'), 10);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/android/build/a.o'), 200);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/android/src/keep.kt'), 10);
    file(join(store, 'pkg-a@1/node_modules/pkg-a/ios/build/keep.o'), 10);
    link(join(store, 'pkg-a@1/node_modules/pkg-a'), join(client, 'node_modules/pkg-a'));

    await cleanAndroid(client);

    expect(existsSync(join(client, 'android/app/src/main/keep.kt'))).toBe(true);
    expect(existsSync(join(store, 'pkg-a@1/node_modules/pkg-a/android/src/keep.kt'))).toBe(true);
    expect(existsSync(join(store, 'pkg-a@1/node_modules/pkg-a/ios/build/keep.o'))).toBe(true);
    expect(existsSync(join(client, 'node_modules/pkg-a'))).toBe(true);
  });

  it('frees nothing and does not fail when there is nothing to delete', async () => {
    const client = join(root, 'client');
    mkdirSync(join(client, 'node_modules'), { recursive: true });
    expect((await cleanAndroid(client)).bytesFreed).toBe(0);
  });

  it('does not follow symbolic links inside a deleted directory', async () => {
    const client = join(root, 'client');
    const outside = join(root, 'outside');
    file(join(outside, 'precious.txt'), 5);
    link(outside, join(client, 'android/build/linked'));

    await cleanAndroid(client);

    expect(existsSync(join(client, 'android/build'))).toBe(false);
    expect(existsSync(join(outside, 'precious.txt'))).toBe(true);
  });
});
