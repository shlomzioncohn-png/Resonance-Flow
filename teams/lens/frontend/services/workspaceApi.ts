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
  /**
   * One of 8 canonical statuses: NEW, IN_REVIEW, APPROVED, IN_PROGRESS,
   * SNOOZED, DONE, REJECTED, CANCELLED.
   * See docs/employee-workspace-api-contract.md §5.0 for status model.
   */
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
  /** Whether the task is available (no blockers) - computed by Chain */
  isAvailable?: boolean
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

// ── Task list ("My Tasks" screen) ──────────────────────────────────────────

/**
 * Filter values for task list.
 * Note: The backend supports filtering by individual status values
 * (new, in_review, approved, in_progress, snoozed, done, rejected, cancelled)
 * in addition to these common aggregate filters.
 * See docs/employee-workspace-api-contract.md §4.5.
 */
export type TaskListFilter = 'all' | 'new' | 'in_review' | 'approved' | 'in_progress' | 'snoozed' | 'done' | 'rejected' | 'cancelled'

export interface TaskListParams {
  filter?: TaskListFilter
  /** Free-text, matched against task title (case-insensitive). */
  search?: string
  /** 0-based page index. */
  page?: number
  /** Page size, clamped server-side to 1-100. */
  size?: number
}

export interface TaskListResponse {
  items: ApiTask[]
  total: number
  page: number
  size: number
}

export function listTasks(params: TaskListParams = {}): Promise<TaskListResponse> {
  const query = new URLSearchParams()
  if (params.filter) query.set('filter', params.filter)
  if (params.search) query.set('search', params.search)
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))

  const queryString = query.toString()
  return api.get<TaskListResponse>(`/api/workspace/tasks${queryString ? `?${queryString}` : ''}`)
}

export type TaskStatus = 'NEW' | 'IN_REVIEW' | 'APPROVED' | 'IN_PROGRESS' | 'SNOOZED' | 'DONE' | 'REJECTED' | 'CANCELLED'

export function updateTaskStatus(taskId: string, status: TaskStatus): Promise<ApiTask> {
  return api.patch<ApiTask>(`/api/workspace/tasks/${taskId}/status`, { status })
}
