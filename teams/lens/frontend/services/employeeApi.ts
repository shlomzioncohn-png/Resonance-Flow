/**
 * Employee API — typed wrapper around the employee's own task record (My
 * Workspace's chat/blocking-dependency demo). No Mind/Chain/Inbox endpoint
 * exists for this yet (see docs/employee-ui-design.md §4.B), so in
 * development this request is answered by an MSW handler
 * (mocks/handlers.ts) instead of a real backend - see that file for why
 * that's a real fetch() call and not just an imported constant.
 */
import { api } from './api'

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

export function getMyTask(): Promise<EmployeeTask> {
  return api.get<EmployeeTask>('/api/employee/task')
}
