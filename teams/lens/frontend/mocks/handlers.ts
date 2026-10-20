/**
 * MSW request handlers - mock data for endpoints the Gateway doesn't expose yet.
 * Each `http.<method>(url, resolver)` entry intercepts real fetch() calls matching
 * that URL and returns a fake Response instead of hitting the network.
 * 
 * Everything NOT matched by a handler here passes straight through to the real Gateway
 * (`onUnhandledRequest: 'bypass'` in MockingProvider).
 *
 * Current status:
 * - POST /api/auth/login: REMOVED (Gateway now handles this with real Inbox)
 * - GET /api/users: Still mocked (Gateway doesn't expose user listing yet)
 * 
 * Once the Gateway serves an endpoint, delete its handler - the calling code already
 * has its final shape and keeps working unchanged.
 */
import { delay, http, HttpResponse } from 'msw';
import type { GatewayErrorBody } from '@/services/api';
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

// Test accounts - REMOVED, using Gateway instead.
// These used to be used when Gateway wasn't ready yet.
// Now the Gateway handles login via POST /auth/login with real Inbox credentials.
// 
// For local testing with real Inbox, use credentials from teams/inbox/src/main/resources/import.sql
// Example test user: testuser123@example.com / Password123!

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

  // POST /api/auth/login - REMOVED, Gateway handles this now.
  // The Gateway validates credentials against real Inbox and returns JWT token.
  // MSW will bypass this (onUnhandledRequest: 'bypass' in MockingProvider),
  // so the real Gateway endpoint will handle the request.
  // (old handler removed - no longer needed)
];
