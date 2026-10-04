import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchGatewayHealth } from '@/services/gatewayHealth';

describe('fetchGatewayHealth', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('calls GET /health (not a BFF-proxied path) and returns the parsed response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () =>
        Promise.resolve({
          status: 'UP',
          service: 'gateway',
          version: '0.1.0',
          timestamp: '2026-09-01T00:00:00Z',
          dependencies: { bff: 'DOWN', bff_url: 'http://bff-service:8080/health' },
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchGatewayHealth();

    expect(fetchMock.mock.calls[0][0]).toMatch(/\/health$/);
    expect(result.status).toBe('UP');
    expect(result.dependencies?.bff).toBe('DOWN');
  });
});
