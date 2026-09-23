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
 * so the calling code (services/managerApi.ts) is real code that will work
 * unchanged against a real backend later. services/employeeApi.ts used to be
 * mocked here too, until the BFF grew a real (BFF-side-mocked) endpoint for
 * it — see com.rnd.bff.client.EmployeeClient.
 */
import { delay, http, HttpResponse } from 'msw'
import type { WorkloadEntry } from '@/services/managerApi'
import type { Project } from '@/services/projectsApi'

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'

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

  // Admin Projects Details endpoints
  // Handles GET /api/admin/projects/{projectId} requests from manager dashboard project details page
  // Returns full project details including team, process stages, and activity feed
  http.get(`${API_BASE_URL}/api/admin/projects/:projectId`, async ({ params }) => {
    await delay(400)

    const projectId = params.projectId as string
    
    // Mock project details by ID (for p1, p2, p3 - aligns with manager projects dashboard)
    const projectDetails: Record<string, Record<string, unknown>> = {
      p1: {
        projectId: 'p1',
        projectName: 'Checkout Revamp',
        description: 'Modernizing the checkout flow with improved UX and faster processing',
        status: 'healthy',
        startDate: 'Feb 1, 2025',
        endDate: 'Jun 30, 2025',
        totalTasks: 18,
        inProgressTasks: 6,
        blockedTasks: 0,
        completedTasks: 13,
        progressPercentage: 72,
        processStages: [
          { name: 'Planning', count: 2 },
          { name: 'In Progress', count: 3 },
          { name: 'Review', count: 2 },
          { name: 'Testing', count: 1 },
          { name: 'Done', count: 10 },
        ],
        team: [
          { id: '1', name: 'Dana Cohen', role: 'Tech Lead', avatar: 'https://i.pravatar.cc/150?img=1', activeTasks: 4, workload: 'At Capacity' },
          { id: '2', name: 'Noa Levi', role: 'Senior Engineer', avatar: 'https://i.pravatar.cc/150?img=3', activeTasks: 3, workload: 'Available' },
          { id: '3', name: 'Omer Katz', role: 'Backend Engineer', avatar: 'https://i.pravatar.cc/150?img=5', activeTasks: 2, workload: 'Available' },
        ],
        recentActivities: [
          { id: '1', action: 'completed', description: 'Payment form validation', actor: { name: 'Dana Cohen', avatar: 'https://i.pravatar.cc/150?img=1' }, timestamp: '1h ago', type: 'success' },
          { id: '2', action: 'merged', description: 'Address auto-fill feature', actor: { name: 'Noa Levi', avatar: 'https://i.pravatar.cc/150?img=3' }, timestamp: '3h ago', type: 'success' },
          { id: '3', action: 'commented', description: 'on security review', actor: { name: 'Omer Katz', avatar: 'https://i.pravatar.cc/150?img=5' }, timestamp: '5h ago' },
        ],
      },
      p2: {
        projectId: 'p2',
        projectName: 'Fraud Detection API',
        description: 'Implementing ML-based fraud detection with real-time alerting',
        status: 'warning',
        startDate: 'Jan 15, 2025',
        endDate: 'May 31, 2025',
        totalTasks: 22,
        inProgressTasks: 9,
        blockedTasks: 2,
        completedTasks: 9,
        progressPercentage: 41,
        processStages: [
          { name: 'Planning', count: 3 },
          { name: 'In Progress', count: 8 },
          { name: 'Review', count: 2 },
          { name: 'Testing', count: 1 },
          { name: 'Done', count: 8 },
        ],
        team: [
          { id: '4', name: 'Roni Peretz', role: 'Tech Lead', avatar: 'https://i.pravatar.cc/150?img=2', activeTasks: 6, workload: 'Overloaded' },
          { id: '5', name: 'Itay Ben David', role: 'ML Engineer', avatar: 'https://i.pravatar.cc/150?img=4', activeTasks: 4, workload: 'At Capacity' },
        ],
        recentActivities: [
          { id: '1', action: 'blocked', description: 'ML model training - waiting for data', actor: { name: 'Roni Peretz', avatar: 'https://i.pravatar.cc/150?img=2' }, timestamp: '2h ago', type: 'warning' },
          { id: '2', action: 'assigned', description: 'Performance optimization to Itay', actor: { name: 'System', avatar: 'https://i.pravatar.cc/150?img=0' }, timestamp: '1d ago' },
        ],
      },
      p3: {
        projectId: 'p3',
        projectName: 'Mobile Onboarding',
        description: 'New mobile app onboarding flow - critical for Q2 launch',
        status: 'critical',
        startDate: 'Dec 1, 2024',
        endDate: 'Mar 31, 2025',
        totalTasks: 15,
        inProgressTasks: 3,
        blockedTasks: 5,
        completedTasks: 2,
        progressPercentage: 13,
        processStages: [
          { name: 'Planning', count: 4 },
          { name: 'In Progress', count: 2 },
          { name: 'Review', count: 1 },
          { name: 'Testing', count: 0 },
          { name: 'Done', count: 2 },
        ],
        team: [
          { id: '6', name: 'Shira Adler', role: 'Product Manager', avatar: 'https://i.pravatar.cc/150?img=6', activeTasks: 5, workload: 'Overloaded' },
          { id: '7', name: 'Yotam Barak', role: 'iOS Lead', avatar: 'https://i.pravatar.cc/150?img=7', activeTasks: 4, workload: 'Overloaded' },
          { id: '8', name: 'Maya Segal', role: 'Android Lead', avatar: 'https://i.pravatar.cc/150?img=8', activeTasks: 3, workload: 'At Capacity' },
          { id: '9', name: 'Eli Nahum', role: 'Backend Engineer', avatar: 'https://i.pravatar.cc/150?img=9', activeTasks: 2, workload: 'Available' },
        ],
        recentActivities: [
          { id: '1', action: 'escalated', description: 'Critical blocker - API design mismatch', actor: { name: 'Shira Adler', avatar: 'https://i.pravatar.cc/150?img=6' }, timestamp: '30m ago', type: 'error' },
          { id: '2', action: 'blocked', description: 'Android build failing - awaiting fix', actor: { name: 'Yotam Barak', avatar: 'https://i.pravatar.cc/150?img=7' }, timestamp: '1h ago', type: 'error' },
          { id: '3', action: 'commented', description: 'Needs design review before coding', actor: { name: 'Eli Nahum', avatar: 'https://i.pravatar.cc/150?img=9' }, timestamp: '3h ago' },
        ],
      },
    }

    const project = projectDetails[projectId]
    if (!project) {
      return HttpResponse.json({ message: 'Project not found' }, { status: 404 })
    }

    return HttpResponse.json(project)
  }),
]
