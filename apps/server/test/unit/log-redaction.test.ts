import { describe, expect, it } from 'vitest';
import Fastify from 'fastify';
import { loggerOptions, REDACT_PATHS } from '../../src/http/log-redaction.ts';
import { testSettings } from '../integration/helpers.ts';

const CENSOR = '[redacted]';
const WILDCARD_PREFIX = '*.';

function capture() {
  const lines: Record<string, unknown>[] = [];
  const app = Fastify({
    logger: {
      ...loggerOptions(testSettings({ LOG_LEVEL: 'info' })),
      stream: { write: (line: string) => lines.push(JSON.parse(line) as Record<string, unknown>) },
    },
  });
  return { log: app.log, lines };
}

describe('log redaction', () => {
  it('redacts a secret at the top level of a log object', () => {
    const { log, lines } = capture();
    log.info({ token: 'x' });
    expect(lines[0]?.['token']).toBe(CENSOR);
  });

  it('redacts a secret one level down', () => {
    const { log, lines } = capture();
    log.info({ body: { loginKey: 'secret-value' } });
    expect(JSON.stringify(lines[0])).not.toContain('secret-value');
  });

  it('redacts the authorization header of a logged request', () => {
    const { log, lines } = capture();
    log.info({ req: { headers: { authorization: 'Bearer secret-value' } } });
    expect(JSON.stringify(lines[0])).not.toContain('secret-value');
  });

  it('has a bare top-level path for every wildcard path', () => {
    const wildcards = REDACT_PATHS.filter((path) => path.startsWith(WILDCARD_PREFIX));
    expect(wildcards.length).toBeGreaterThan(0);
    for (const path of wildcards) {
      expect(REDACT_PATHS).toContain(path.slice(WILDCARD_PREFIX.length));
    }
  });
});
