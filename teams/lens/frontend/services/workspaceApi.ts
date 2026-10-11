/**
 * Workspace API — typed wrapper around the Gateway's /api/workspace endpoints.
 * Mirrors the BFF DTOs in teams/lens/bff/src/main/java/com/rnd/bff/dto/.
 */
import { api } from './api'

// ── Request ────────────────────────────────────────────────────────────────

export interface WorkspaceRequest {
  availableTime: number
  projectFilter?: string
  minPriority?: 'LOW' | 'MEDIUM' | 'HIGH'
}

// ── Task shape from the BFF ────────────────────────────────────────────────

export interface ApiTask {
  id: string
  title: string
  description: string
  /** READY | IN_PROGRESS | BLOCKED | COMPLETED */
  status: string
  /** LOW | MEDIUM | HIGH */
  priority: string
  /** Estimated effort in minutes */
  effortEstimate: number
  /** ISO-8601 datetime string */
  dueDate?: string
  owner: string
  project: string
  /** IDs of tasks this task depends on */
  dependencies?: string[]
  /** Blocker labels, e.g. "approval_needed" */
  constraints?: string[]
}

// ── Recommendation response ────────────────────────────────────────────────

export interface TaskRecommendation {
  task: ApiTask
  explanation: string
  fitsInTime: boolean
  isHighPriority: boolean
  hasNoDependencies: boolean
  /** 0–100 */
  confidenceScore: number
  alternatives: ApiTask[]
}

// ── API call ───────────────────────────────────────────────────────────────

export function recommendTask(request: WorkspaceRequest): Promise<TaskRecommendation> {
  return api.post<TaskRecommendation>('/api/workspace/recommend', request)
}

export function getTask(taskId: string): Promise<ApiTask> {
  return api.get<ApiTask>(`/api/workspace/tasks/${taskId}`)
}

export type TaskStatus = 'READY' | 'IN_PROGRESS' | 'BLOCKED' | 'COMPLETED'

/** Error code the backend returns when a status change is not allowed (e.g. moving a DONE task). */
export const INVALID_TASK_STATUS_TRANSITION = 'INVALID_TASK_STATUS_TRANSITION'

/**
 * A finished task can't move to any other status. Inbox calls it DONE, the BFF DTO
 * documents COMPLETED — accept both until the name is settled.
 */
export function isFinalStatus(status: string): boolean {
  const normalised = status.toUpperCase()
  return normalised === 'DONE' || normalised === 'COMPLETED'
}

export function updateTaskStatus(taskId: string, status: TaskStatus): Promise<ApiTask> {
  return api.patch<ApiTask>(`/api/workspace/tasks/${taskId}/status`, { status })
}
