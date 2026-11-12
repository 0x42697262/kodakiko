import pg from 'pg';
import { DEFAULT_TEST_DATABASE_URL } from './test-constants.ts';

const CONNECT_DEADLINE_MS = 10_000;
const RETRY_DELAY_MS = 500;
const COMPOSE_COMMAND = 'docker compose -f infra/compose/docker-compose.test.yml up -d';

async function canConnect(url: string): Promise<boolean> {
  const client = new pg.Client({
    connectionString: url,
    connectionTimeoutMillis: RETRY_DELAY_MS * 4,
  });
  try {
    await client.connect();
    return true;
  } catch {
    return false;
  } finally {
    await client.end().catch(() => undefined);
  }
}

export default async function setup(): Promise<void> {
  const url = process.env['TEST_DATABASE_URL'] ?? DEFAULT_TEST_DATABASE_URL;
  const deadline = Date.now() + CONNECT_DEADLINE_MS;
  while (Date.now() < deadline) {
    if (await canConnect(url)) return;
    await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY_MS));
  }
  throw new Error(`Cannot connect to the test database. Start it with: ${COMPOSE_COMMAND}`);
}
