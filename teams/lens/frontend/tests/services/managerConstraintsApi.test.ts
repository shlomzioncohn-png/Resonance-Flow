import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api';
import { getTaskConstraints, updateConstraintStatus } from '@/services/managerApi';

function respondWith(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: status < 400,
    status,
    statusText: '',
    json: () => Promise.resolve(body),
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('manager constraint API', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("gets the task's constraints from the Gateway", async () => {
    const constraints = [{ constraintId: 'c1', type: 'APPROVAL_NEEDED', status: 'PENDING', reason: {} }];
    const fetchMock = respondWith(200, constraints);

    await expect(getTaskConstraints('t-1')).resolves.toEqual(constraints);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/manager/tasks/t-1/constraints')).toBe(true);
    expect(init.method).toBeUndefined();
  });

  it('PATCHes the new status and resolves to the saved constraint', async () => {
    const saved = { constraintId: 'c1', type: 'APPROVAL_NEEDED', status: 'SATISFIED', reason: {} };
    const fetchMock = respondWith(200, saved);

    await expect(updateConstraintStatus('t-1', 'c1', 'SATISFIED')).resolves.toEqual(saved);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/manager/tasks/t-1/constraints/c1')).toBe(true);
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body)).toEqual({ status: 'SATISFIED' });
  });

  it('encodes the ids as single path segments', async () => {
    const fetchMock = respondWith(200, {});

    await updateConstraintStatus('t/1', 'c 1', 'WAIVED');

    const [url] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/manager/tasks/t%2F1/constraints/c%201')).toBe(true);
  });

  it("rejects with the Gateway's status when the constraint doesn't exist", async () => {
    respondWith(404, { status: 'ERROR', errorCode: 'NOT_FOUND', message: 'Not found', path: '', timestamp: '' });

    const error = await updateConstraintStatus('t-1', 'missing', 'WAIVED').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).code).toBe('NOT_FOUND');
  });
});
