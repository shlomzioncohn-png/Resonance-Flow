import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, getErrorMessage } from '@/services/api';

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('get() resolves with the parsed JSON body on a 2xx response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ status: 'UP', service: 'gateway' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get<{ status: string; service: string }>('/health');

    expect(result).toEqual({ status: 'UP', service: 'gateway' });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url.endsWith('/health')).toBe(true);
    expect(init.headers['Content-Type']).toBe('application/json');
  });

  it('get() resolves undefined on a 204 No Content response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      json: () => Promise.resolve(undefined),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await api.get('/some-empty-endpoint');

    expect(result).toBeUndefined();
  });

  it('get() throws ApiError with the Gateway error contract fields on a non-2xx response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      statusText: 'Service Unavailable',
      json: () =>
        Promise.resolve({
          status: 'ERROR',
          errorCode: 'SERVICE_UNAVAILABLE',
          message: 'Backend service unavailable',
          path: '/api/status',
          timestamp: '2026-09-01T00:00:00Z',
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.get('/api/status')).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Backend service unavailable',
      status: 503,
      path: '/api/status',
    });
  });

  it('get() falls back to response.statusText when the error body is not valid JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      statusText: 'Bad Gateway',
      json: () => Promise.reject(new Error('not json')),
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.get('/broken')).rejects.toMatchObject({
      message: 'Bad Gateway',
      status: 502,
    });
  });

  it('post() sends the method and JSON-stringified body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ created: true }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await api.post('/things', { name: 'a task' });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ name: 'a task' }));
  });

  it('post() sends no body when called without one', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      json: () => Promise.resolve(undefined),
    });
    vi.stubGlobal('fetch', fetchMock);

    await api.post('/ping');

    const [, init] = fetchMock.mock.calls[0];
    expect(init.body).toBeUndefined();
  });
});

describe('getErrorMessage', () => {
  const apiError = (status: number) =>
    new ApiError('Downstream service returned an error', status, '/x');

  it.each([
    [400, "This change isn't allowed for this task."],
    [401, "We couldn't verify who you are. Please sign in again."],
    [403, "You don't have access to this task."],
    [404, "This task wasn't found. It may have been removed."],
    [503, 'The task service is temporarily unavailable. Please try again in a moment.'],
    [504, 'The task service is temporarily unavailable. Please try again in a moment.'],
    [500, 'Something went wrong. Please try again.'],
  ])('maps HTTP %i to a user-facing message, not the Gateway text', (status, expected) => {
    expect(getErrorMessage(apiError(status))).toBe(expected);
  });

  it('treats a non-ApiError (fetch failed, Gateway unreachable) as a connection problem', () => {
    expect(getErrorMessage(new TypeError('Failed to fetch'))).toBe(
      'The task service is unreachable. Check your connection and try again.',
    );
  });
});
