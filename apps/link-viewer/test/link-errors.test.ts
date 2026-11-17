import { describe, expect, it } from 'vitest';
import { DecryptionError } from '@kodakiko/core';
import { ApiError, NetworkError } from '../src/http.ts';
import { FAILURE_KINDS, failureKind, failureMessage, type FailureKind } from '../src/link-errors.ts';
import { LinkFetchError } from '../src/link-session.ts';
import { TEXT } from '../src/text.ts';

function apiError(code: ConstructorParameters<typeof ApiError>[1], status = 403): ApiError {
  return new ApiError(status, code, null, 'req-1');
}

describe('failureKind (twin of the LinkViewerScreen failureMessage, plus offline)', () => {
  it('maps the server denials of a link', () => {
    expect(failureKind(apiError('LINK_TOKEN_INVALID'), null)).toBe('invalid');
    expect(failureKind(apiError('LINK_EXPIRED'), null)).toBe('expired');
    expect(failureKind(apiError('LINK_EXPIRED'), 1)).toBe('expired');
  });

  it('tells an ended viewing session from a spent one-view link', () => {
    expect(failureKind(apiError('LINK_VIEW_EXPIRED'), null)).toBe('sessionEnded');
    expect(failureKind(apiError('LINK_VIEW_EXPIRED'), 5)).toBe('sessionEnded');
    expect(failureKind(apiError('LINK_VIEW_EXPIRED'), 1)).toBe('oneViewEnded');
  });

  it('reports a missing connection as offline instead of a broken link', () => {
    expect(failureKind(new NetworkError(), null)).toBe('offline');
  });

  it('does not reveal anything else: other API errors, decryption failures and unknown errors are an invalid link', () => {
    expect(failureKind(apiError('NOT_FOUND', 404), null)).toBe('invalid');
    expect(failureKind(apiError('VALIDATION_FAILED', 400), null)).toBe('invalid');
    expect(failureKind(apiError('FEATURE_DISABLED', 404), null)).toBe('invalid');
    expect(failureKind(apiError('INTERNAL', 500), null)).toBe('invalid');
    expect(failureKind(new DecryptionError('bad'), null)).toBe('invalid');
    expect(failureKind(new LinkFetchError(403), null)).toBe('invalid');
    expect(failureKind(new Error('boom'), null)).toBe('invalid');
    expect(failureKind('not an error', null)).toBe('invalid');
  });
});

describe('failureMessage', () => {
  it('has one message per failure kind, all from the text catalog', () => {
    const expected: Record<FailureKind, string> = {
      incomplete: TEXT.incomplete,
      invalid: TEXT.invalid,
      expired: TEXT.expired,
      sessionEnded: TEXT.sessionEnded,
      oneViewEnded: TEXT.oneViewEnded,
      offline: TEXT.offline,
    };
    expect([...FAILURE_KINDS].sort()).toEqual(Object.keys(expected).sort());
    for (const kind of FAILURE_KINDS) expect(failureMessage(kind)).toBe(expected[kind]);
  });

  it('keeps the wording of the v0.3 web viewer', () => {
    expect(failureMessage('incomplete')).toBe('This link is incomplete. Ask for the full link.');
    expect(failureMessage('invalid')).toBe("This link doesn't work. It may have been revoked or mistyped.");
    expect(failureMessage('expired')).toBe('This link has expired.');
    expect(failureMessage('sessionEnded')).toBe('This viewing session ended. Open the original link again.');
    expect(failureMessage('oneViewEnded')).toBe('This link could only be viewed once.');
  });

  it('asks the visitor to reconnect when offline', () => {
    expect(failureMessage('offline')).toBe("You're offline. Connect to the internet and open the link again.");
  });
});
