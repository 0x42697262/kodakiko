import { execFileSync } from 'node:child_process';

export type LicensePackage = { readonly name: string; readonly versions: readonly string[]; readonly license: string };
export type LicenseReport = Readonly<Record<string, readonly LicensePackage[]>>;
export type LicenseRow = { readonly name: string; readonly version: string; readonly license: string };

export const ALLOWED_LICENSES: ReadonlySet<string> = new Set([
  'MIT',
  'ISC',
  'BSD-2-Clause',
  'BSD-3-Clause',
  'Apache-2.0',
  '0BSD',
  'BlueOak-1.0.0',
  'CC0-1.0',
  'Unlicense',
  'MPL-2.0',
  'Python-2.0',
  'CC-BY-4.0',
]);

const TOKEN = /\(|\)|[^\s()]+/g;
const MAX_BUFFER_BYTES = 268_435_456;

class ParseFailure extends Error {}

export function licenseAllowed(expression: string): boolean {
  const tokens = expression.match(TOKEN) ?? [];
  let position = 0;
  const peek = (): string | undefined => tokens[position];
  const take = (): string => {
    const token = tokens[position];
    if (token === undefined) throw new ParseFailure(expression);
    position += 1;
    return token;
  };
  const atom = (): boolean => {
    const token = take();
    if (token === '(') {
      const value = or();
      if (take() !== ')') throw new ParseFailure(expression);
      return value;
    }
    if (token === ')' || token === 'AND' || token === 'OR') throw new ParseFailure(expression);
    return ALLOWED_LICENSES.has(token);
  };
  const and = (): boolean => {
    let value = atom();
    while (peek() === 'AND') {
      take();
      value = atom() && value;
    }
    return value;
  };
  function or(): boolean {
    let value = and();
    while (peek() === 'OR') {
      take();
      value = and() || value;
    }
    return value;
  }
  try {
    const value = or();
    return position === tokens.length && value;
  } catch (error) {
    if (error instanceof ParseFailure) return false;
    throw error;
  }
}

export function licenseList(report: LicenseReport): LicenseRow[] {
  const rows: LicenseRow[] = [];
  for (const packages of Object.values(report)) {
    for (const item of packages) {
      for (const version of item.versions) rows.push({ name: item.name, version, license: item.license });
    }
  }
  return rows.sort((left, right) => left.name.localeCompare(right.name) || left.version.localeCompare(right.version));
}

export function checkLicenses(report: LicenseReport): string[] {
  return licenseList(report)
    .filter((row) => !licenseAllowed(row.license))
    .map((row) => `${row.name}@${row.version} (${row.license})`);
}

if (import.meta.main) {
  const output = execFileSync('pnpm', ['licenses', 'list', '--json', '--prod', '--recursive'], {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER_BYTES,
  });
  const report = JSON.parse(output) as LicenseReport;
  const offenders = checkLicenses(report);
  if (offenders.length > 0) {
    process.stderr.write(`Licenses outside D-70:\n${offenders.join('\n')}\n`);
    process.exit(1);
  }
  process.stdout.write('licenses ok\n');
}
