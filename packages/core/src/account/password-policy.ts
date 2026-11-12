import { PASSWORD_MIN_CODE_POINTS } from './constants.ts';
import { normalizeUsername } from './username.ts';

export type PasswordRule = 'tooShort' | 'equalsUsername' | 'singleRepeatedCharacter';

export function checkPasswordPolicy(
  password: string,
  normalizedUsername: string,
): readonly PasswordRule[] {
  const codePoints = [...password.normalize('NFKC')];
  const broken: PasswordRule[] = [];
  if (codePoints.length < PASSWORD_MIN_CODE_POINTS) broken.push('tooShort');
  if (normalizeUsername(password) === normalizedUsername) broken.push('equalsUsername');
  if (new Set(codePoints).size === 1) broken.push('singleRepeatedCharacter');
  return broken;
}
