import { describe, expect, it } from 'vitest';
import { checkLicenses, licenseAllowed, licenseList, type LicenseReport } from '../check-licenses.ts';

const REPORT: LicenseReport = {
  MIT: [{ name: 'b-lib', versions: ['2.0.0', '1.0.0'], license: 'MIT' }],
  '(MIT OR GPL-3.0)': [{ name: 'a-dual', versions: ['1.0.0'], license: '(MIT OR GPL-3.0)' }],
  UNLICENSED: [{ name: 'secret', versions: ['0.1.0'], license: 'UNLICENSED' }],
};

describe('licenseAllowed', () => {
  it('accepts every license of D-70', () => {
    for (const id of ['MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0', '0BSD', 'BlueOak-1.0.0', 'CC0-1.0', 'Unlicense', 'MPL-2.0', 'Python-2.0', 'CC-BY-4.0']) {
      expect(licenseAllowed(id)).toBe(true);
    }
  });

  it('accepts an OR expression with one allowed alternative', () => {
    expect(licenseAllowed('(BSD-3-Clause OR GPL-2.0)')).toBe(true);
    expect(licenseAllowed('GPL-2.0 OR GPL-3.0')).toBe(false);
  });

  it('accepts an AND expression only when every part is allowed, with parentheses', () => {
    expect(licenseAllowed('MIT AND (Apache-2.0 OR GPL-3.0)')).toBe(true);
    expect(licenseAllowed('MIT AND GPL-3.0')).toBe(false);
    expect(licenseAllowed('(MIT AND ISC) OR GPL-3.0')).toBe(true);
  });

  it('rejects UNLICENSED, unknown strings and broken expressions', () => {
    expect(licenseAllowed('UNLICENSED')).toBe(false);
    expect(licenseAllowed('SEE LICENSE IN LICENSE.md')).toBe(false);
    expect(licenseAllowed('GPL-3.0')).toBe(false);
    expect(licenseAllowed('(MIT OR')).toBe(false);
    expect(licenseAllowed('')).toBe(false);
  });
});

describe('checkLicenses', () => {
  it('lists offenders with their versions', () => {
    expect(checkLicenses(REPORT)).toEqual(['secret@0.1.0 (UNLICENSED)']);
  });

  it('builds the sorted licenses.json list, one row per version', () => {
    expect(licenseList(REPORT)).toEqual([
      { name: 'a-dual', version: '1.0.0', license: '(MIT OR GPL-3.0)' },
      { name: 'b-lib', version: '1.0.0', license: 'MIT' },
      { name: 'b-lib', version: '2.0.0', license: 'MIT' },
      { name: 'secret', version: '0.1.0', license: 'UNLICENSED' },
    ]);
  });
});
