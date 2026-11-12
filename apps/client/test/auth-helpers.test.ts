import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError } from '../src/adapters/http/http';
import { pickConfirmPositions } from '../src/features/auth/confirm-positions';
import { errorBanner } from '../src/features/auth/error-text';
import { extractInviteToken } from '../src/features/auth/invite-input';
import { passwordHints } from '../src/features/auth/password-hints';
import { AuthError, toAuthError } from '../src/services/auth-errors';

const TOKEN = `kdk_i_${'A'.repeat(43)}`;
const WORD_TOTAL = 24;
const CONFIRM_COUNT = 3;

describe('invite input', () => {
  it('accepts an invite link, a bare token and rejects anything else', () => {
    expect(extractInviteToken(`https://journal.example.test/invite#${TOKEN}`)).toBe(TOKEN);
    expect(extractInviteToken(`  ${TOKEN}  `)).toBe(TOKEN);
    expect(extractInviteToken('https://journal.example.test/invite')).toBeNull();
    expect(extractInviteToken('not a link')).toBeNull();
  });
});

describe('confirm positions', () => {
  it('returns three distinct ascending one-based positions inside the word count', () => {
    const stream = [200, 5, 5, 30, 255, 47, 12, 100];
    let next = 0;
    const randomBytes = () => Uint8Array.of(stream[next++ % stream.length] ?? 0);
    const positions = pickConfirmPositions(randomBytes, WORD_TOTAL, CONFIRM_COUNT);
    expect(positions).toHaveLength(CONFIRM_COUNT);
    expect(new Set(positions).size).toBe(CONFIRM_COUNT);
    expect([...positions].sort((left, right) => left - right)).toEqual(positions);
    for (const position of positions) {
      expect(position).toBeGreaterThanOrEqual(1);
      expect(position).toBeLessThanOrEqual(WORD_TOTAL);
    }
  });

  it('rejects random values that would bias the result', () => {
    const stream = [255, 254, 0, 1, 2];
    let next = 0;
    const randomBytes = () => Uint8Array.of(stream[next++] ?? 0);
    expect(pickConfirmPositions(randomBytes, WORD_TOTAL, CONFIRM_COUNT)).toEqual([1, 2, 3]);
  });
});

describe('password hints', () => {
  it('shows nothing before the field was touched and each failing rule after', () => {
    expect(passwordHints('short', 'alice', false)).toEqual([]);
    expect(passwordHints('short', 'alice', true)).toEqual(['tooShort']);
    expect(passwordHints('aaaaaaaaaaaa', 'alice', true)).toEqual(['singleRepeatedCharacter']);
  });
});

describe('auth errors', () => {
  it('maps transport and API failures to a reason', () => {
    expect(toAuthError(new NetworkError()).reason).toBe('unreachable');
    expect(toAuthError(new ApiError(401, 'INVALID_CREDENTIALS', null, null)).reason).toBe(
      'invalidCredentials',
    );
    expect(toAuthError(new ApiError(403, 'INVITE_INVALID', null, null)).reason).toBe(
      'inviteInvalid',
    );
    expect(toAuthError(new ApiError(409, 'USERNAME_TAKEN', null, null)).reason).toBe(
      'usernameTaken',
    );
    expect(toAuthError(new ApiError(429, 'RATE_LIMITED', null, null)).reason).toBe('rateLimited');
    expect(toAuthError(new ApiError(500, 'INTERNAL', null, null)).reason).toBe('generic');
  });

  it('rethrows unexpected errors', () => {
    expect(() => toAuthError(new TypeError('bug'))).toThrow(TypeError);
  });

  it('gives the two credential failures one text and the word position its own text', () => {
    expect(errorBanner(new AuthError('wrongPassword')).message).toBe(
      errorBanner(new AuthError('invalidCredentials')).message,
    );
    expect(errorBanner(new AuthError('recoveryWordsInvalid', { position: 5 })).message).toBe(
      "Word 5 isn't in the recovery word list.",
    );
    expect(errorBanner(new AuthError('recoveryWordsInvalid')).testID).toBe(
      'errors.recoveryWordsInvalid',
    );
  });
});
