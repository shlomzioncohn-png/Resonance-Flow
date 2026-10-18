/**
 * MSW request handlers - the mock data, and the rules for which requests get
 * mocked. Each `http.<method>(url, resolver)` entry intercepts real fetch()
 * calls matching that URL and returns a fake Response instead of hitting the
 * network. Everything NOT matched by a handler here passes straight through
 * to the real Gateway (`onUnhandledRequest: 'bypass'` in MockingProvider), so
 * the /api/workspace/* and /api/manager/* calls are untouched by MSW.
 *
 * Only endpoints the Gateway doesn't expose yet belong here. Once the Gateway
 * serves one, delete its handler - the calling code (services/usersApi.ts,
 * services/authApi.ts) already has its final shape and keeps working unchanged.
 */
import { delay, http, HttpResponse } from 'msw';
import type { GatewayErrorBody } from '@/services/api';
import type { LoginRequest, LoginResponse } from '@/services/authApi';
import type { User, UsersPage } from '@/services/usersApi';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

const DEFAULT_PAGE_SIZE = 20;

// Field names and enum values match Inbox's user response (UserResponseDto).
const MOCK_USERS: User[] = [
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01',
    displayName: 'Dana Cohen',
    email: 'dana.cohen@example.com',
    role: 'TENANT_ADMIN',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-13T08:42:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c02',
    displayName: 'Roni Peretz',
    email: 'roni.peretz@example.com',
    role: 'TEAM_LEAD',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-13T07:15:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c03',
    displayName: 'Shira Adler',
    email: 'shira.adler@example.com',
    role: 'TEAM_LEAD',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-12T16:30:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c04',
    displayName: 'Noa Levi',
    email: 'noa.levi@example.com',
    role: 'EMPLOYEE',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-13T09:05:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c05',
    displayName: 'Omer Katz',
    email: 'omer.katz@example.com',
    role: 'EMPLOYEE',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-10T11:20:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c06',
    displayName: 'Itay Ben David',
    email: 'itay.bendavid@example.com',
    role: 'EMPLOYEE',
    status: 'PENDING',
    lastLoginAt: null,
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c07',
    displayName: 'Yotam Barak',
    email: 'yotam.barak@example.com',
    role: 'EMPLOYEE',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-09T14:48:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c08',
    displayName: 'Maya Segal',
    email: 'maya.segal@example.com',
    role: 'EMPLOYEE',
    status: 'SUSPENDED',
    lastLoginAt: '2026-09-21T10:02:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c09',
    displayName: 'Eli Nahum',
    email: 'eli.nahum@example.com',
    role: 'EMPLOYEE',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-11T18:27:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c10',
    displayName: 'Guy Refael',
    email: 'guy.refael@example.com',
    role: 'TEAM_LEAD',
    status: 'PENDING',
    lastLoginAt: null,
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c11',
    displayName: 'Tal Mizrahi',
    email: 'tal.mizrahi@example.com',
    role: 'EMPLOYEE',
    status: 'DISABLED',
    lastLoginAt: '2026-07-02T09:40:00Z',
  },
  {
    id: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c12',
    displayName: 'Lior Shani',
    email: 'lior.shani@example.com',
    role: 'EMPLOYEE',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-12T12:11:00Z',
  },
];

// Same users and passwords as the Keycloak realm (teams/lens/keycloak/realm-lens.json).
const MOCK_ACCOUNTS: (LoginRequest & LoginResponse)[] = [
  {
    username: 'manager1',
    password: 'manager1',
    id: 'a3c5e0b2-4f1d-4c8e-9b7a-1d2e3f4a5b01',
    displayName: 'Maya Manager',
    role: 'manager',
  },
  {
    username: 'employee1',
    password: 'employee1',
    id: 'a3c5e0b2-4f1d-4c8e-9b7a-1d2e3f4a5b02',
    displayName: 'Eli Employee',
    role: 'employee',
  },
];

/** A non-negative integer query param, or `fallback` when it's missing or invalid. */
function intParam(value: string | null, fallback: number, min: number): number {
  const parsed = Number(value);
  return value !== null && Number.isInteger(parsed) && parsed >= min ? parsed : fallback;
}

export const handlers = [
  // GET /api/users?page=&size= - `page` is zero-based, like Inbox's own paging.
  http.get(`${API_BASE_URL}/api/users`, async ({ request }) => {
    await delay(400);

    const params = new URL(request.url).searchParams;
    const page = intParam(params.get('page'), 0, 0);
    const size = intParam(params.get('size'), DEFAULT_PAGE_SIZE, 1);

    return HttpResponse.json<UsersPage>({
      items: MOCK_USERS.slice(page * size, (page + 1) * size),
      total: MOCK_USERS.length,
    });
  }),

  // POST /api/auth/login - a wrong username or password gets the Gateway's 401 error body.
  // The generics type the request body and allow either response body (success or error).
  http.post<never, LoginRequest, LoginResponse | GatewayErrorBody>(
    `${API_BASE_URL}/api/auth/login`,
    async ({ request }) => {
      await delay(400);

      const { username, password } = await request.json();
      const account = MOCK_ACCOUNTS.find(
        (candidate) => candidate.username === username && candidate.password === password,
      );

      if (!account) {
        return HttpResponse.json<GatewayErrorBody>(
          {
            status: 'ERROR',
            errorCode: 'UNAUTHORIZED',
            message: 'Invalid username or password',
            path: '/api/auth/login',
            timestamp: new Date().toISOString(),
          },
          { status: 401 },
        );
      }

      return HttpResponse.json<LoginResponse>({
        id: account.id,
        displayName: account.displayName,
        role: account.role,
      });
    },
  ),
];
