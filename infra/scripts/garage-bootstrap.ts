export type BootstrapOptions = {
  readonly adminUrl: string;
  readonly adminToken: string;
  readonly bucket: string;
  readonly keyName: string;
  readonly capacityBytes: number;
  readonly attempts: number;
  readonly pauseMs: number;
  readonly fetcher?: typeof fetch;
};

export type BootstrapResult = { readonly accessKeyId: string; readonly secretAccessKey: string | null };

type NodeStatus = { readonly id: string; readonly role: unknown };

const NOT_FOUND = 404;
const ZONE = 'dc1';

export async function bootstrapGarage(options: BootstrapOptions): Promise<BootstrapResult> {
  const fetcher = options.fetcher ?? fetch;
  async function call(path: string, body?: unknown, allowNotFound = false): Promise<unknown> {
    const response = await fetcher(`${options.adminUrl}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { authorization: `Bearer ${options.adminToken}`, 'content-type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (allowNotFound && response.status === NOT_FOUND) return null;
    if (!response.ok) throw new Error(`Garage admin API ${path} answered ${response.status}`);
    return response.json();
  }

  let status: { readonly nodes: readonly NodeStatus[] } | null = null;
  for (let attempt = 1; status === null; attempt += 1) {
    try {
      status = (await call('/v2/GetClusterStatus')) as { readonly nodes: readonly NodeStatus[] };
    } catch (error) {
      if (attempt >= options.attempts) throw error;
      await new Promise((resolve) => setTimeout(resolve, options.pauseMs));
    }
  }
  const node = status.nodes[0];
  if (node === undefined) throw new Error('Garage reports no node');
  if (node.role === null) {
    const layout = (await call('/v2/GetClusterLayout')) as { readonly version: number };
    await call('/v2/UpdateClusterLayout', { roles: [{ id: node.id, zone: ZONE, capacity: options.capacityBytes, tags: [] }] });
    await call('/v2/ApplyClusterLayout', { version: layout.version + 1 });
  }

  const alias = encodeURIComponent(options.bucket);
  const bucket =
    ((await call(`/v2/GetBucketInfo?globalAlias=${alias}`, undefined, true)) as { readonly id: string } | null) ??
    ((await call('/v2/CreateBucket', { globalAlias: options.bucket })) as { readonly id: string });

  const keys = (await call('/v2/ListKeys')) as readonly { readonly id: string; readonly name: string }[];
  const existing = keys.find((key) => key.name === options.keyName);
  let result: BootstrapResult;
  if (existing === undefined) {
    const created = (await call('/v2/CreateKey', { name: options.keyName })) as {
      readonly accessKeyId: string;
      readonly secretAccessKey: string;
    };
    result = { accessKeyId: created.accessKeyId, secretAccessKey: created.secretAccessKey };
  } else {
    result = { accessKeyId: existing.id, secretAccessKey: null };
  }
  await call('/v2/AllowBucketKey', {
    bucketId: bucket.id,
    accessKeyId: result.accessKeyId,
    permissions: { read: true, write: true, owner: true },
  });
  return result;
}

const DEFAULT_CAPACITY_BYTES = 1_000_000_000_000;
const STATUS_ATTEMPTS = 30;
const STATUS_PAUSE_MS = 1_000;

if (import.meta.main) {
  const adminToken = process.env['GARAGE_ADMIN_TOKEN'] ?? '';
  if (adminToken === '') {
    process.stderr.write('GARAGE_ADMIN_TOKEN is not set.\n');
    process.exit(1);
  }
  const result = await bootstrapGarage({
    adminUrl: process.env['GARAGE_ADMIN_URL'] ?? 'http://garage:3903',
    adminToken,
    bucket: process.env['S3_BUCKET'] ?? 'kodakiko',
    keyName: process.env['GARAGE_KEY_NAME'] ?? 'kodakiko-api',
    capacityBytes: Number(process.env['GARAGE_CAPACITY_BYTES'] ?? DEFAULT_CAPACITY_BYTES),
    attempts: STATUS_ATTEMPTS,
    pauseMs: STATUS_PAUSE_MS,
  });
  process.stdout.write(`S3_ACCESS_KEY_ID=${result.accessKeyId}\n`);
  process.stdout.write(
    result.secretAccessKey === null
      ? 'The key already existed; its secret was printed when it was created. Create a new key to get a new secret.\n'
      : `S3_SECRET_ACCESS_KEY=${result.secretAccessKey}\nCopy both lines into .env now; the secret is not shown again.\n`,
  );
}
