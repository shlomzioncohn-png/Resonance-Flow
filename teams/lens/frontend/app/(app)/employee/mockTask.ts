export interface TaskRef {
  id: string
  title: string
  owner: string
}

export interface EmployeeTask {
  id: string
  title: string
  team: string
  priority: 'High' | 'Medium' | 'Low'
  status: 'Ready' | 'Blocked'
  description: string
  owner: string
  due: string
  estimatedMinutes: number
  createdAt: string
  source: string
  sprint: string
  tags: string[]
  blockedBy: TaskRef[]
  blocks: TaskRef[]
  blockingReason: string | null
}

// Seed value for the future Chain/Inbox-backed task record (see docs/employee-ui-design.md)
// — no Mind/Chain/Inbox endpoint exists yet, so this single mock task is reused across the
// My Workspace recommendation and the Task Details drill-down, held as state by TaskProvider
// (./taskStore.tsx) so a chat action can update it and both screens see the change.
export const INITIAL_TASK: EmployeeTask = {
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
