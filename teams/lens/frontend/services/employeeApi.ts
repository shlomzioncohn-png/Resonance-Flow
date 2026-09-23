/**
 * Employee API — typed wrapper around the employee's own task record (My
 * Workspace's chat/blocking-dependency demo). No Mind/Chain/Inbox service
 * owns this data yet (see docs/employee-ui-design.md §4.B), so it's mocked
 * one layer down, at the BFF (com.rnd.bff.client.EmployeeClient) — this call
 * is real, straight through the Gateway, same as services/workspaceApi.ts.
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
  return api.get<EmployeeTask>('/api/workspace/employee-task')
}
