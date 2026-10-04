import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/services/api';

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
          status: 503,
          error: 'SERVICE_UNAVAILABLE',
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
