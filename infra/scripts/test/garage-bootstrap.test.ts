import { describe, expect, it } from 'vitest';
import { bootstrapGarage } from '../garage-bootstrap.ts';

type Call = { readonly path: string; readonly body: unknown };

function fakeGarage(initial: { hasRole: boolean; bucket: boolean; key: boolean }) {
  const calls: Call[] = [];
  const state = { ...initial };
  const fetcher = async (url: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const parsed = new URL(String(url));
    const body = init?.body === undefined ? null : JSON.parse(String(init.body));
    calls.push({ path: parsed.pathname + parsed.search, body });
    if (new Headers(init?.headers).get('authorization') !== 'Bearer admin') return new Response('', { status: 403 });
    const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });
    switch (parsed.pathname) {
      case '/v2/GetClusterStatus':
        return json({ nodes: [{ id: 'node1', role: state.hasRole ? { zone: 'dc1' } : null }] });
      case '/v2/GetClusterLayout':
        return json({ version: 3 });
      case '/v2/UpdateClusterLayout':
        return json({});
      case '/v2/ApplyClusterLayout':
        state.hasRole = true;
        return json({});
      case '/v2/GetBucketInfo':
        return state.bucket ? json({ id: 'bucket1' }) : new Response('', { status: 404 });
      case '/v2/CreateBucket':
        state.bucket = true;
        return json({ id: 'bucket1' });
      case '/v2/ListKeys':
        return json(state.key ? [{ id: 'GKkey', name: 'kodakiko-api' }] : []);
      case '/v2/CreateKey':
        state.key = true;
        return json({ accessKeyId: 'GKkey', secretAccessKey: 'secret-value' });
      case '/v2/AllowBucketKey':
        return json({});
      default:
        return new Response('', { status: 404 });
    }
  };
  return { calls, fetcher };
}

const OPTIONS = { adminUrl: 'http://garage:3903', adminToken: 'admin', bucket: 'kodakiko', keyName: 'kodakiko-api', capacityBytes: 1_000, attempts: 1, pauseMs: 0 };

describe('garage bootstrap', () => {
  it('assigns the layout, creates the bucket and key, grants access and returns the new secret', async () => {
    const garage = fakeGarage({ hasRole: false, bucket: false, key: false });
    const result = await bootstrapGarage({ ...OPTIONS, fetcher: garage.fetcher });
    expect(result).toEqual({ accessKeyId: 'GKkey', secretAccessKey: 'secret-value' });
    const paths = garage.calls.map((call) => call.path.split('?')[0]);
    expect(paths).toContain('/v2/ApplyClusterLayout');
    expect(garage.calls.find((call) => call.path === '/v2/UpdateClusterLayout')?.body).toEqual({
      roles: [{ id: 'node1', zone: 'dc1', capacity: 1_000, tags: [] }],
    });
    expect(garage.calls.find((call) => call.path === '/v2/ApplyClusterLayout')?.body).toEqual({ version: 4 });
    expect(garage.calls.find((call) => call.path === '/v2/AllowBucketKey')?.body).toEqual({
      bucketId: 'bucket1',
      accessKeyId: 'GKkey',
      permissions: { read: true, write: true, owner: true },
    });
  });

  it('is idempotent: a second run changes nothing and returns no secret', async () => {
    const garage = fakeGarage({ hasRole: true, bucket: true, key: true });
    const result = await bootstrapGarage({ ...OPTIONS, fetcher: garage.fetcher });
    expect(result).toEqual({ accessKeyId: 'GKkey', secretAccessKey: null });
    const paths = garage.calls.map((call) => call.path.split('?')[0]);
    expect(paths).not.toContain('/v2/UpdateClusterLayout');
    expect(paths).not.toContain('/v2/CreateBucket');
    expect(paths).not.toContain('/v2/CreateKey');
  });

  it('fails clearly when the admin token is wrong', async () => {
    const garage = fakeGarage({ hasRole: true, bucket: true, key: true });
    await expect(bootstrapGarage({ ...OPTIONS, adminToken: 'wrong', fetcher: garage.fetcher })).rejects.toThrow(/403/);
  });
});
