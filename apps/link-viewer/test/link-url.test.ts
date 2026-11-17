import { describe, expect, it } from 'vitest';
import { encodeBytes } from '@kodakiko/contract';
import { LINK_PATH_PREFIX, LINK_SECRET_BYTES, parseLinkFragment, parseLinkLocation, parseLinkPath } from '../src/link-url.ts';

const LINK_ID = 'L'.repeat(22);
const SECRET = Uint8Array.from({ length: 32 }, (_, index) => index);

describe('parseLinkFragment (twin of apps/client link url)', () => {
  it('round-trips the secret through the fragment and rejects bad fragments', () => {
    const url = `https://a.example/s/${LINK_ID}#${encodeBytes(SECRET)}`;
    expect(parseLinkFragment(new URL(url).hash)).toEqual(SECRET);
    expect(parseLinkFragment(encodeBytes(SECRET))).toEqual(SECRET);
    expect(parseLinkFragment('')).toBeNull();
    expect(parseLinkFragment('#')).toBeNull();
    expect(parseLinkFragment('#short')).toBeNull();
    expect(parseLinkFragment('#!!!')).toBeNull();
  });

  it('accepts only exactly 32 bytes', () => {
    expect(LINK_SECRET_BYTES).toBe(32);
    expect(parseLinkFragment(`#${encodeBytes(new Uint8Array(31))}`)).toBeNull();
    expect(parseLinkFragment(`#${encodeBytes(new Uint8Array(33))}`)).toBeNull();
    expect(parseLinkFragment(`#${encodeBytes(new Uint8Array(32))}`)).toEqual(new Uint8Array(32));
  });

  it('rejects a fragment cut in the middle of the secret', () => {
    const encoded = encodeBytes(SECRET);
    expect(parseLinkFragment(`#${encoded.slice(0, encoded.length - 1)}`)).toBeNull();
    expect(parseLinkFragment(`#${encoded}x`)).toBeNull();
  });
});

describe('parseLinkPath', () => {
  it('reads the link id from /s/<id>', () => {
    expect(LINK_PATH_PREFIX).toBe('/s/');
    expect(parseLinkPath(`/s/${LINK_ID}`)).toBe(LINK_ID);
    expect(parseLinkPath(`/s/${LINK_ID}/`)).toBe(LINK_ID);
    expect(parseLinkPath('/s/abcdefghijklmnopqrstu_')).toBe('abcdefghijklmnopqrstu_');
  });

  it('refuses paths that are not a link with a valid id', () => {
    expect(parseLinkPath('/')).toBeNull();
    expect(parseLinkPath('/s/')).toBeNull();
    expect(parseLinkPath('/s/short')).toBeNull();
    expect(parseLinkPath(`/s/${LINK_ID}x`)).toBeNull();
    expect(parseLinkPath(`/s/${'L'.repeat(21)}!`)).toBeNull();
    expect(parseLinkPath(`/x/${LINK_ID}`)).toBeNull();
    expect(parseLinkPath(`/s/${LINK_ID}/extra`)).toBeNull();
  });
});

describe('parseLinkLocation', () => {
  it('returns the link id and secret for a complete link', () => {
    expect(parseLinkLocation(`/s/${LINK_ID}`, `#${encodeBytes(SECRET)}`)).toEqual({ ok: true, linkId: LINK_ID, secret: SECRET });
  });

  it('reports a missing or broken fragment as an incomplete link', () => {
    expect(parseLinkLocation(`/s/${LINK_ID}`, '')).toEqual({ ok: false, failure: 'incomplete' });
    expect(parseLinkLocation(`/s/${LINK_ID}`, '#abc')).toEqual({ ok: false, failure: 'incomplete' });
  });

  it('reports a path without a valid link id as an invalid link, even with a good fragment', () => {
    expect(parseLinkLocation('/s/nope', `#${encodeBytes(SECRET)}`)).toEqual({ ok: false, failure: 'invalid' });
    expect(parseLinkLocation('/s/nope', '')).toEqual({ ok: false, failure: 'invalid' });
  });
});
