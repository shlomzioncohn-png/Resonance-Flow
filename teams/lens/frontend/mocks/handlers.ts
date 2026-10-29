/**
 * MSW request handlers - the mock data, and the rules for which requests get
 * mocked. Each `http.<method>(url, resolver)` entry intercepts real fetch()
 * calls matching that URL and returns a fake Response instead of hitting the
 * network. Everything NOT matched by a handler here passes straight through
 * to the real Gateway (`onUnhandledRequest: 'bypass'` in MockingProvider), so
 * the /api/workspace/* calls are untouched by MSW.
 *
 * What's here:
 * - The Manager's Users screen (/api/manager/users). The Gateway does serve it,
 *   but for real it needs Gateway + BFF + Inbox + Postgres running. With
 *   NEXT_PUBLIC_API_MOCKING=enabled the whole screen - list, create, edit,
 *   status change - works with nothing but `npm run dev`. The data lives in
 *   memory: changes survive until the page is reloaded.
 * - POST /auth/login and /auth/logout. The Gateway serves them for real (against
 *   Inbox's users); the mock keeps the Login screen working without them, and
 *   backs its stories.
 * - GET /api/manager/bottlenecks/graph, the Bottlenecks screen's dependency
 *   graph - only as exported handlers for the stories. The app always gets
 *   Chain's real graph from the Gateway. The sample data lives in
 *   mocks/dependencyGraphData.ts, shared with the DependencyGraph stories.
 * - GET /api/manager/dashboard/projects, the Dashboard's charts - only as
 *   exported handlers for the stories. The app always gets the real counts
 *   from the Gateway, like the KPIs.
 *
 * The calling code (services/managerUsersApi.ts, services/authApi.ts) is the
 * same either way. Storybook uses these same handlers (.storybook/preview.tsx),
 * so the app in mock mode and the stories always see the same fake Gateway.
 */
import { delay, http, HttpResponse } from 'msw';
import type { GatewayErrorBody } from '@/services/api';
import type { AuthRole, LoginRequest, LoginResponse } from '@/services/authApi';
import type { DependencyGraph } from '@/services/bottlenecksApi';
import type { ProjectTaskCounts } from '@/services/managerApi';
import type {
  UserCreateFields,
  UserDetails,
  UserEditFields,
  UserListResponse,
  UserStatus,
} from '@/services/managerUsersApi';
import type { FlowHealth } from '@/services/flowHealthApi';
import { sampleProjectTaskCounts } from './dashboardChartsData';
import { emptyGraph, sampleGraph, sampleGraphForProject } from './dependencyGraphData';
import { emptyFlowHealth, sampleFlowHealth } from './flowHealthData';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
export const USERS_URL = `${API_BASE_URL}/api/manager/users`;
export const BOTTLENECKS_GRAPH_URL = `${API_BASE_URL}/api/manager/bottlenecks/graph`;
export const FLOW_HEALTH_URL = `${API_BASE_URL}/api/manager/flow-health`;

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

// Same users and passwords as the Keycloak realm (teams/lens/keycloak/realm-lens.json).
const MOCK_ACCOUNTS: (LoginRequest & { password: string; id: string; displayName: string; role: AuthRole })[] = [
  {
    login: 'manager1',
    password: 'manager1',
    id: 'a3c5e0b2-4f1d-4c8e-9b7a-1d2e3f4a5b01',
    displayName: 'Maya Manager',
    role: 'TENANT_ADMIN',
  },
  {
    login: 'employee1',
    password: 'employee1',
    id: 'a3c5e0b2-4f1d-4c8e-9b7a-1d2e3f4a5b02',
    displayName: 'Eli Employee',
    role: 'EMPLOYEE',
  },
];

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

/**
 * Alternatives to the graph handler below, for stories (`msw.use(...)`) and
 * for trying the screen's other states by hand: no dependencies at all, and a
 * server error.
 */
const PROJECT_TASK_COUNTS_URL = `${API_BASE_URL}/api/manager/dashboard/projects`;

/**
 * The Dashboard's charts, for stories only - not in `handlers`, so in mock
 * mode the app gets the real counts from the Gateway, same as the KPIs.
 */
export const projectTaskCountsHandler = http.get(PROJECT_TASK_COUNTS_URL, async () => {
  await delay(400);
  return HttpResponse.json<ProjectTaskCounts[]>(sampleProjectTaskCounts);
});

/** The Dashboard's charts with no tasks at all - for stories. */
export const projectTaskCountsEmptyHandler = http.get(PROJECT_TASK_COUNTS_URL, () =>
  HttpResponse.json<ProjectTaskCounts[]>([]),
);

/** The sample graph (?projectId= keeps one project's tasks) - for stories. */
export const bottlenecksGraphHandler = http.get(BOTTLENECKS_GRAPH_URL, async ({ request }) => {
  await delay(400);
  const projectId = new URL(request.url).searchParams.get('projectId');
  return HttpResponse.json<DependencyGraph>(projectId ? sampleGraphForProject(projectId) : sampleGraph);
});

export const bottlenecksGraphEmptyHandler = http.get(BOTTLENECKS_GRAPH_URL, async () => {
  await delay(300);
  return HttpResponse.json<DependencyGraph>(emptyGraph);
});

export const bottlenecksGraphErrorHandler = http.get(BOTTLENECKS_GRAPH_URL, async ({ request }) => {
  await delay(300);
  return errorResponse(
    500,
    'INTERNAL_SERVER_ERROR',
    'Downstream service returned an error',
    new URL(request.url).pathname,
  );
});

/** Flow Health with no flags yet, and a Chain outage (the BFF answers 503). */
export const flowHealthEmptyHandler = http.get(FLOW_HEALTH_URL, async () => {
  await delay(300);
  return HttpResponse.json<FlowHealth>(emptyFlowHealth);
});

export const flowHealthUnavailableHandler = http.get(FLOW_HEALTH_URL, async ({ request }) => {
  await delay(300);
  return errorResponse(
    503,
    'SERVICE_UNAVAILABLE',
    'Downstream service is unavailable. Please try again later.',
    new URL(request.url).pathname,
  );
});

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

  // POST /auth/login - a wrong email or password gets the Gateway's 401 error body.
  // The generics type the request body and allow either response body (success or error).
  http.post<never, LoginRequest, LoginResponse | GatewayErrorBody>(
    `${API_BASE_URL}/auth/login`,
    async ({ request }) => {
      await delay(400);

      const { login, password } = await request.json();
      const account = MOCK_ACCOUNTS.find(
        // Like Keycloak, accept the username or the realm email (the login field is type="email").
        (candidate) =>
          (candidate.login === login || `${candidate.login}@lens.local` === login) &&
          candidate.password === password,
      );

      if (!account) {
        return HttpResponse.json<GatewayErrorBody>(
          {
            status: 'ERROR',
            errorCode: 'UNAUTHORIZED',
            message: 'Invalid email or password',
            path: '/auth/login',
            timestamp: new Date().toISOString(),
          },
          { status: 401 },
        );
      }

      // The real Gateway also sets the token as an HttpOnly cookie; the mock has no token at all.
      return HttpResponse.json<LoginResponse>({
        expiresIn: 3600,
        user: {
          id: account.id,
          displayName: account.displayName,
          role: account.role,
        },
      });
    },
  ),

  // POST /auth/logout - the real Gateway clears the token cookie; the mock has none to clear.
  http.post(`${API_BASE_URL}/auth/logout`, () => new HttpResponse(null, { status: 204 })),

  // GET /api/manager/flow-health - read-only; there is no write handler on purpose.
  http.get(FLOW_HEALTH_URL, async () => {
    await delay(400);
    return HttpResponse.json<FlowHealth>(sampleFlowHealth);
  }),
];
