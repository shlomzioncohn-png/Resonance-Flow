/**
 * MSW request handlers - the actual mock data, and the rules for which
 * requests get mocked. Each `http.<method>(url, resolver)` entry intercepts
 * real fetch() calls matching that exact URL and returns a fake Response
 * instead of hitting the network. Everything NOT matched by a handler here
 * passes straight through to the real network - see mocks/browser.ts's
 * `onUnhandledRequest: 'bypass'`, which is why the already-working
 * /api/workspace/* calls (services/workspaceApi.ts) are untouched by MSW.
 *
 * Replaces the old static mocks (app/(app)/employee/mockTask.ts,
 * mocks/managerWorkload.ts): the DATA is the same, but it's now served
 * through a real network round-trip instead of imported as a plain object,
 * so the calling code (services/employeeApi.ts, services/managerApi.ts)
 * is real code that will work unchanged against a real backend later.
 */
import { delay, http, HttpResponse } from 'msw'
import type { EmployeeTask } from '@/services/employeeApi'
import type { WorkloadEntry } from '@/services/managerApi'

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'

// Same seed data app/(app)/employee/mockTask.ts used to export as INITIAL_TASK.
const MOCK_TASK: EmployeeTask = {
  id: 'task-1',
  title: 'Review API changes',
  team: 'Platform Service',
  priority: 'High',
  status: 'Blocked',
  description: 'Review the latest API changes and update integration tests accordingly.',
  owner: 'Rachel',
  due: 'Today, 5:00 PM',
  estimatedMinutes: 35,
  createdAt: 'May 10, 9:15 AM',
  source: 'GitHub — PR #4821',
  sprint: 'Sprint 24 (May 6 – May 20)',
  tags: ['backend', 'api', 'testing'],
  blockedBy: [{ id: 'task-0', title: 'Update API specification', owner: 'Alex Morgan' }],
  blocks: [
    { id: 'task-2', title: 'Run regression tests', owner: 'Taylor Smith' },
    { id: 'task-3', title: 'Update client SDK', owner: 'Sam Cohen' },
  ],
  blockingReason: 'Waiting for the API specification to be finalized.',
}

export const handlers = [
  http.get(`${API_BASE_URL}/api/employee/task`, async () => {
    await delay(300)
    return HttpResponse.json(MOCK_TASK)
  }),

  http.get(`${API_BASE_URL}/api/manager/workload`, async ({ request }) => {
    await delay(700)

    // Reads the query param services/managerApi.ts's getManagerWorkload()
    // sets when its `simulateError` argument is true - a handler can
    // inspect anything about the request (URL, headers, body) to decide
    // how to respond, same as a real server would.
    const simulateError = new URL(request.url).searchParams.get('simulateError') === 'true'
    if (simulateError) {
      return HttpResponse.json(
        { message: 'Simulated failure — demonstrates the ErrorState.' },
        { status: 500 },
      )
    }

    return HttpResponse.json<WorkloadEntry[]>([])
  }),
]
