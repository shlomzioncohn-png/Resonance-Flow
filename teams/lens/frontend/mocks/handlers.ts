/**
 * MSW request handlers - the mock data, and the rules for which requests get
 * mocked. Each `http.<method>(url, resolver)` entry intercepts real fetch()
 * calls matching that URL and returns a fake Response instead of hitting the
 * network. Everything NOT matched by a handler here passes straight through
 * to the real Gateway (`onUnhandledRequest: 'bypass'` in MockingProvider), so
 * the /api/workspace/* calls are untouched by MSW.
 *
 * What's here: the Manager's Users screen (/api/manager/users). The Gateway
 * does serve it, but for real it needs Gateway + BFF + Inbox + Postgres
 * running. With NEXT_PUBLIC_API_MOCKING=enabled the whole screen - list,
 * create, edit, status change - works with nothing but `npm run dev`. The
 * calling code (services/managerUsersApi.ts) is the same either way.
 *
 * The data lives in memory: changes survive until the page is reloaded.
 *
 * Storybook uses these same handlers (.storybook/preview.tsx), so the app in
 * mock mode and the stories always see the same fake Gateway.
 */
import { delay, http, HttpResponse } from 'msw';
import type {
  UserCreateFields,
  UserDetails,
  UserEditFields,
  UserListResponse,
  UserStatus,
} from '@/services/managerUsersApi';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
export const USERS_URL = `${API_BASE_URL}/api/manager/users`;

const DEFAULT_PAGE_SIZE = 20;

function mockUser(
  suffix: string,
  displayName: string,
  role: 'MANAGER' | 'EMPLOYEE',
  status: string,
  lastLoginAt?: string,
): UserDetails {
  const [firstName, ...rest] = displayName.split(' ');
  const username = displayName.toLowerCase().replace(/ /g, '.');
  return {
    id: `7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c${suffix}`,
    displayName,
    firstName,
    lastName: rest.join(' '),
    email: `${username}@example.com`,
    username,
    role,
    status,
    lastLoginAt,
    // Dana is "the acting manager" - the BFF marks the caller's own row.
    currentUser: suffix === '01',
    createdAt: '2026-09-01T09:00:00Z',
    updatedAt: '2026-09-01T09:00:00Z',
  };
}

// Field names and enum values match the Gateway's UserDetailsDTO (LENS roles: MANAGER | EMPLOYEE).
const INITIAL_USERS: UserDetails[] = [
  mockUser('01', 'Dana Cohen', 'MANAGER', 'ACTIVE', '2026-10-13T08:42:00Z'),
  mockUser('02', 'Roni Peretz', 'MANAGER', 'ACTIVE', '2026-10-13T07:15:00Z'),
  mockUser('03', 'Shira Adler', 'EMPLOYEE', 'ACTIVE', '2026-10-12T16:30:00Z'),
  mockUser('04', 'Noa Levi', 'EMPLOYEE', 'ACTIVE', '2026-10-13T09:05:00Z'),
  mockUser('05', 'Omer Katz', 'EMPLOYEE', 'ACTIVE', '2026-10-10T11:20:00Z'),
  mockUser('06', 'Itay Ben David', 'EMPLOYEE', 'PENDING'),
  mockUser('07', 'Yotam Barak', 'EMPLOYEE', 'ACTIVE', '2026-10-09T14:48:00Z'),
  mockUser('08', 'Maya Segal', 'EMPLOYEE', 'SUSPENDED', '2026-09-21T10:02:00Z'),
  mockUser('09', 'Eli Nahum', 'EMPLOYEE', 'ACTIVE', '2026-10-11T18:27:00Z'),
  mockUser('10', 'Guy Refael', 'EMPLOYEE', 'PENDING'),
  mockUser('11', 'Tal Mizrahi', 'EMPLOYEE', 'DISABLED', '2026-07-02T09:40:00Z'),
  mockUser('12', 'Lior Shani', 'EMPLOYEE', 'ACTIVE', '2026-10-12T12:11:00Z'),
];

let users: UserDetails[] = [];

/** Puts the 12 users back as they started - Storybook calls it before every story. */
export function resetMockUsers() {
  users = INITIAL_USERS.map((user) => ({ ...user }));
}
resetMockUsers();

/** A non-negative integer query param, or `fallback` when it's missing or invalid. */
function intParam(value: string | null, fallback: number, min: number): number {
  const parsed = Number(value);
  return value !== null && Number.isInteger(parsed) && parsed >= min ? parsed : fallback;
}

/** Same error shape the Gateway sends (GatewayErrorBody in services/api.ts). */
function errorResponse(status: number, errorCode: string, message: string, path: string) {
  return HttpResponse.json(
    { status: 'ERROR', errorCode, message, path, timestamp: new Date().toISOString() },
    { status },
  );
}

function notFound(path: string) {
  return errorResponse(404, 'NOT_FOUND', 'User not found', path);
}

function touch(user: UserDetails, changes: Partial<UserDetails>): UserDetails {
  Object.assign(user, changes, { updatedAt: new Date().toISOString() });
  return user;
}

export const handlers = [
  // GET /api/manager/users?page=&size=&sort=field,dir - `page` is zero-based.
  http.get(USERS_URL, async ({ request }) => {
    await delay(400);

    const params = new URL(request.url).searchParams;
    const page = intParam(params.get('page'), 0, 0);
    const size = intParam(params.get('size'), DEFAULT_PAGE_SIZE, 1);
    const [sortField = 'displayName', sortDir = 'asc'] = (params.get('sort') ?? '').split(',');

    const sorted = [...users].sort((a, b) => {
      const left = String(a[sortField as keyof UserDetails] ?? '');
      const right = String(b[sortField as keyof UserDetails] ?? '');
      return sortDir === 'desc' ? right.localeCompare(left) : left.localeCompare(right);
    });

    return HttpResponse.json<UserListResponse>({
      items: sorted.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: users.length,
      totalPages: Math.ceil(users.length / size),
    });
  }),

  http.get(`${USERS_URL}/:id`, async ({ params, request }) => {
    await delay(200);
    const user = users.find((u) => u.id === params.id);
    return user ? HttpResponse.json(user) : notFound(new URL(request.url).pathname);
  }),

  // Same rules as the BFF: the tenant is never taken from the client, and a duplicate email is 409.
  http.post(USERS_URL, async ({ request }) => {
    await delay(400);
    const fields = (await request.json()) as UserCreateFields;
    const path = new URL(request.url).pathname;

    if (users.some((u) => u.email.toLowerCase() === fields.email.toLowerCase())) {
      return errorResponse(409, 'CONFLICT', 'A user with this email already exists', path);
    }

    const now = new Date().toISOString();
    const created: UserDetails = {
      id: crypto.randomUUID(),
      displayName: fields.displayName,
      firstName: fields.firstName,
      lastName: fields.lastName,
      email: fields.email,
      phone: fields.phone,
      username: fields.username ?? fields.email,
      role: fields.role,
      status: 'PENDING',
      createdAt: now,
      updatedAt: now,
    };
    users = [...users, created];
    return HttpResponse.json(created, { status: 201 });
  }),

  http.patch(`${USERS_URL}/:id/status`, async ({ params, request }) => {
    await delay(300);
    const user = users.find((u) => u.id === params.id);
    if (!user) return notFound(new URL(request.url).pathname);
    const { status } = (await request.json()) as { status: UserStatus };
    return HttpResponse.json(touch(user, { status }));
  }),

  http.patch(`${USERS_URL}/:id`, async ({ params, request }) => {
    await delay(300);
    const user = users.find((u) => u.id === params.id);
    if (!user) return notFound(new URL(request.url).pathname);
    const fields = (await request.json()) as UserEditFields;
    return HttpResponse.json(touch(user, fields));
  }),
];
