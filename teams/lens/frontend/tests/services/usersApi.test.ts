import { afterEach, describe, expect, it, vi } from 'vitest';
import { getUsers } from '@/services/usersApi';

describe('getUsers', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function stubFetch(body: unknown) {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(body),
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('asks the Gateway for the first page by default', async () => {
    const usersPage = {
      items: [
        {
          id: 'u1',
          displayName: 'Dana Cohen',
          email: 'dana.cohen@example.com',
          role: 'TENANT_ADMIN',
          status: 'ACTIVE',
          lastLoginAt: '2026-10-13T08:42:00Z',
        },
      ],
      total: 1,
    };
    const fetchMock = stubFetch(usersPage);

    const result = await getUsers();

    expect(result).toEqual(usersPage);
    const [url] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/users?page=0&size=20')).toBe(true);
  });

  it('sends the requested page and size', async () => {
    const fetchMock = stubFetch({ items: [], total: 0 });

    await getUsers(2, 5);

    const [url] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/users?page=2&size=5')).toBe(true);
  });
});
