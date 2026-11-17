import { describe, expect, it } from 'vitest';
import { forgetMediaOnPageHide } from '../src/media-worker.ts';
import { MEDIA_MESSAGE, type PageToWorkerMessage } from '../src/sw/messages.ts';

function fakeHost() {
  const posted: PageToWorkerMessage[] = [];
  const host: { controller: { postMessage: (message: PageToWorkerMessage) => void } | null } = {
    controller: { postMessage: (message) => posted.push(message) },
  };
  return { host, posted };
}

describe('media worker page lifecycle', () => {
  it('posts forgetAll to the controlling worker when the page is hidden', () => {
    const page = new EventTarget();
    const { host, posted } = fakeHost();
    forgetMediaOnPageHide(page, host);
    expect(posted).toEqual([]);
    page.dispatchEvent(new Event('pagehide'));
    expect(posted).toEqual([{ type: MEDIA_MESSAGE.forgetAll }]);
    page.dispatchEvent(new Event('pagehide'));
    expect(posted).toEqual([{ type: MEDIA_MESSAGE.forgetAll }, { type: MEDIA_MESSAGE.forgetAll }]);
  });

  it('posts forgetAll on teardown and stops listening afterwards', () => {
    const page = new EventTarget();
    const { host, posted } = fakeHost();
    const teardown = forgetMediaOnPageHide(page, host);
    teardown();
    expect(posted).toEqual([{ type: MEDIA_MESSAGE.forgetAll }]);
    page.dispatchEvent(new Event('pagehide'));
    expect(posted).toEqual([{ type: MEDIA_MESSAGE.forgetAll }]);
  });

  it('uses the controller current at pagehide time and tolerates none', () => {
    const page = new EventTarget();
    const { host, posted } = fakeHost();
    const saved = host.controller;
    host.controller = null;
    forgetMediaOnPageHide(page, host);
    expect(() => page.dispatchEvent(new Event('pagehide'))).not.toThrow();
    expect(posted).toEqual([]);
    host.controller = saved;
    page.dispatchEvent(new Event('pagehide'));
    expect(posted).toEqual([{ type: MEDIA_MESSAGE.forgetAll }]);
  });
});
