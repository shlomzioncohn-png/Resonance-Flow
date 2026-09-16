/**
 * Manager API — typed wrapper around the all-projects list with health/KPI
 * data. No project-aggregate endpoint exists yet (only task-level endpoints
 * under /api/workspace/*, see docs/api-integration-status.md), so in
 * development this request is answered by an MSW handler (mocks/handlers.ts)
 * instead of a real backend.
 */
import { api } from './api'
import type { StatusTone } from '@/components/ui/StatusBadge'

export interface ProjectMember {
  name: string
  avatarUrl?: string
}

export interface Project {
  id: string
  name: string
  team: string
  owner: string
  status: StatusTone
  statusLabel: string
  members: ProjectMember[]
  /** 0-100 */
  progressPercent: number
  totalCount: number
  inProgressCount: number
  blockedCount: number
  lastUpdatedAt: string
}

/**
 * simulateError asks the mock handler to respond with a 500 instead of data
 * - see mocks/handlers.ts. Meaningless against a real backend; harmless
 * there too, since a real backend would just ignore an unknown query param.
 */
export function getProjects(simulateError = false): Promise<Project[]> {
  const query = simulateError ? '?simulateError=true' : ''
  return api.get<Project[]>(`/api/manager/projects${query}`)
}
