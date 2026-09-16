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
import type { Project } from '@/services/projectsApi'

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

// Same 12 seed projects the Manager/Projects design was reviewed against —
// health tones interleaved rather than grouped, matching the Figma mock.
const MOCK_PROJECTS: Project[] = [
  { id: 'p1', name: 'Checkout Revamp', team: 'Payments', owner: 'Dana Cohen', status: 'healthy', statusLabel: 'Healthy', members: [{ name: 'Dana Cohen' }, { name: 'Noa Levi' }, { name: 'Omer Katz' }], progressPercent: 72, totalCount: 18, inProgressCount: 6, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 2 * 3600_000).toISOString() },
  { id: 'p2', name: 'Fraud Detection API', team: 'Risk', owner: 'Roni Peretz', status: 'warning', statusLabel: 'At Risk', members: [{ name: 'Roni Peretz' }, { name: 'Itay Ben David' }], progressPercent: 41, totalCount: 22, inProgressCount: 9, blockedCount: 2, lastUpdatedAt: new Date(Date.now() - 5 * 3600_000).toISOString() },
  { id: 'p3', name: 'Mobile Onboarding', team: 'Growth', owner: 'Shira Adler', status: 'critical', statusLabel: 'Critical', members: [{ name: 'Shira Adler' }, { name: 'Yotam Barak' }, { name: 'Maya Segal' }, { name: 'Eli Nahum' }], progressPercent: 18, totalCount: 15, inProgressCount: 3, blockedCount: 5, lastUpdatedAt: new Date(Date.now() - 1 * 3600_000).toISOString() },
  { id: 'p4', name: 'Search Relevance v2', team: 'Platform', owner: 'Guy Refael', status: 'healthy', statusLabel: 'Healthy', members: [{ name: 'Guy Refael' }, { name: 'Tal Mizrahi' }], progressPercent: 88, totalCount: 12, inProgressCount: 2, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 26 * 3600_000).toISOString() },
  { id: 'p5', name: 'Billing Reconciliation', team: 'Payments', owner: 'Dana Cohen', status: 'warning', statusLabel: 'At Risk', members: [{ name: 'Dana Cohen' }, { name: 'Roni Peretz' }, { name: 'Omer Katz' }], progressPercent: 55, totalCount: 20, inProgressCount: 8, blockedCount: 1, lastUpdatedAt: new Date(Date.now() - 3 * 3600_000).toISOString() },
  { id: 'p6', name: 'Design System Rollout', team: 'Platform', owner: 'Tal Mizrahi', status: 'healthy', statusLabel: 'Healthy', members: [{ name: 'Tal Mizrahi' }, { name: 'Shira Adler' }], progressPercent: 64, totalCount: 10, inProgressCount: 3, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 8 * 3600_000).toISOString() },
  { id: 'p7', name: 'CRM Sync Pipeline', team: 'Integrations', owner: 'Eli Nahum', status: 'critical', statusLabel: 'Critical', members: [{ name: 'Eli Nahum' }, { name: 'Maya Segal' }], progressPercent: 9, totalCount: 14, inProgressCount: 1, blockedCount: 6, lastUpdatedAt: new Date(Date.now() - 0.5 * 3600_000).toISOString() },
  { id: 'p8', name: 'Notification Center', team: 'Growth', owner: 'Yotam Barak', status: 'healthy', statusLabel: 'Healthy', members: [{ name: 'Yotam Barak' }, { name: 'Itay Ben David' }, { name: 'Noa Levi' }], progressPercent: 79, totalCount: 16, inProgressCount: 4, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 30 * 3600_000).toISOString() },
  { id: 'p9', name: 'Vendor Risk Scoring', team: 'Risk', owner: 'Roni Peretz', status: 'warning', statusLabel: 'At Risk', members: [{ name: 'Roni Peretz' }], progressPercent: 47, totalCount: 11, inProgressCount: 5, blockedCount: 2, lastUpdatedAt: new Date(Date.now() - 12 * 3600_000).toISOString() },
  { id: 'p10', name: 'Internal Analytics Hub', team: 'Platform', owner: 'Guy Refael', status: 'healthy', statusLabel: 'Healthy', members: [{ name: 'Guy Refael' }, { name: 'Dana Cohen' }], progressPercent: 93, totalCount: 9, inProgressCount: 1, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 48 * 3600_000).toISOString() },
  { id: 'p11', name: 'GitHub App Migration', team: 'Integrations', owner: 'Maya Segal', status: 'critical', statusLabel: 'Critical', members: [{ name: 'Maya Segal' }, { name: 'Eli Nahum' }, { name: 'Omer Katz' }], progressPercent: 22, totalCount: 13, inProgressCount: 2, blockedCount: 4, lastUpdatedAt: new Date(Date.now() - 4 * 3600_000).toISOString() },
  { id: 'p12', name: 'Legacy Contract Import', team: 'Integrations', owner: 'Unassigned', status: 'neutral', statusLabel: 'No data', members: [], progressPercent: 0, totalCount: 0, inProgressCount: 0, blockedCount: 0, lastUpdatedAt: new Date(Date.now() - 240 * 3600_000).toISOString() },
]

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

  http.get(`${API_BASE_URL}/api/manager/projects`, async ({ request }) => {
    await delay(500)

    const simulateError = new URL(request.url).searchParams.get('simulateError') === 'true'
    if (simulateError) {
      return HttpResponse.json(
        { message: 'Simulated failure — demonstrates the ErrorState.' },
        { status: 500 },
      )
    }

    return HttpResponse.json<Project[]>(MOCK_PROJECTS)
  }),
]
