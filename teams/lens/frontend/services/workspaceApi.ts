/**
 * Workspace API — typed wrapper around the Gateway's /api/workspace endpoints.
 * Mirrors the BFF DTOs in teams/lens/bff/src/main/java/com/rnd/bff/dto/.
 */
import { api } from './api';

// ── Request ────────────────────────────────────────────────────────────────

export interface WorkspaceRequest {
  availableTime: number;
  projectFilter?: string;
  minPriority?: 'LOW' | 'MEDIUM' | 'HIGH';
}

// ── Task shape from the BFF ────────────────────────────────────────────────

export interface ApiTask {
  id: string;
  title: string;
  description: string;
  /**
   * One of 8 canonical statuses: NEW, IN_REVIEW, APPROVED, IN_PROGRESS,
   * SNOOZED, DONE, REJECTED, CANCELLED.
   * See docs/employee-workspace-api-contract.md §5.0 for status model.
   */
  status: string;
  /** HIGH/MEDIUM/LOW. Missing when the task has no priority score. */
  priority?: string;
  /** Estimated effort in minutes */
  effortEstimate: number;
  /** Already formatted by Inbox, Israel time, e.g. "12/10/2026" - shown as-is, never parsed */
  dueDate?: string;
  /** Display names. Missing for real Inbox tasks: Inbox only sends ids so far. */
  owner?: string;
  project?: string;
  /** IDs of tasks this task depends on */
  dependencies?: string[];
  /** Blocker labels, e.g. "approval_needed" */
  constraints?: string[];
  /** Whether the task is available (no blockers) - computed by Chain */
  isAvailable?: boolean;
  /** operational / tactical / strategic (lowercase, from Inbox) */
  tier?: string;
  /** 1 (low) - 5 (high), from Inbox */
  urgency?: number;
  /** Where Inbox got the task from, e.g. "email", "jira" (lowercase) */
  sourceType?: string;
  /** Already formatted by Inbox, Israel time, e.g. "12/10/2026 09:00" - shown as-is */
  createdAt?: string;
  updatedAt?: string;
  /** When a SNOOZED task wakes up again - same format as createdAt */
  snoozedUntil?: string;
}

// ── Recommendation response ────────────────────────────────────────────────

export interface TaskRecommendation {
  task: ApiTask;
  explanation: string;
  fitsInTime: boolean;
  isHighPriority: boolean;
  hasNoDependencies: boolean;
  /** 0–100 */
  confidenceScore: number;
  alternatives: ApiTask[];
}

// ── API call ───────────────────────────────────────────────────────────────

export function recommendTask(request: WorkspaceRequest): Promise<TaskRecommendation> {
  return api.post<TaskRecommendation>('/api/workspace/recommend', request);
}

export function getTask(taskId: string): Promise<ApiTask> {
  return api.get<ApiTask>(`/api/workspace/tasks/${taskId}`);
}

// ── Task selection ("which tasks fit in N minutes?") ─────────────────────

export interface SelectedTask {
  id: string;
  title: string;
  description?: string;
  /** Minutes */
  effortEstimate: number;
  /** Already formatted by Inbox, Israel time, e.g. "12/10/2026" - shown as-is, never parsed */
  dueDate?: string;
  /** 0-1, higher is a better pick */
  selectionScore: number;
}

export interface TaskSelection {
  availableMinutes: number;
  /** In suggested execution order. Empty when nothing can be done. */
  selectedTasks: SelectedTask[];
  totalEffortMinutes: number;
  remainingMinutes: number;
  /** True when nothing fit, so the shortest task was picked anyway (it takes longer). */
  usedFallback: boolean;
  /** Why, e.g. "window was automatically expanded to 21 minutes". May be missing. */
  reason?: string;
}

/** The tasks that fit `availableMinutes` (1-480, Inbox's limit) together, picked by Inbox. */
export function selectTasks(availableMinutes: number): Promise<TaskSelection> {
  return api.get<TaskSelection>(
    `/api/workspace/tasks/selection?availableMinutes=${availableMinutes}`,
  );
}

// ── Task list ("My Tasks" screen) ──────────────────────────────────────────

/**
 * Filter values for task list.
 * Note: The backend supports filtering by individual status values
 * (new, in_review, approved, in_progress, snoozed, done, rejected, cancelled)
 * in addition to these common aggregate filters.
 * See docs/employee-workspace-api-contract.md §4.5.
 */
export type TaskListFilter =
  | 'all'
  | 'new'
  | 'in_review'
  | 'approved'
  | 'in_progress'
  | 'snoozed'
  | 'done'
  | 'rejected'
  | 'cancelled';

export interface TaskListParams {
  filter?: TaskListFilter;
  /** Free-text, matched against task title (case-insensitive). */
  search?: string;
  /** 0-based page index. */
  page?: number;
  /** Page size, clamped server-side to 1-100. */
  size?: number;
}

export interface TaskListResponse {
  items: ApiTask[];
  total: number;
  page: number;
  size: number;
}

export function listTasks(params: TaskListParams = {}): Promise<TaskListResponse> {
  const query = new URLSearchParams();
  if (params.filter) query.set('filter', params.filter);
  if (params.search) query.set('search', params.search);
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.size !== undefined) query.set('size', String(params.size));

  const queryString = query.toString();
  return api.get<TaskListResponse>(`/api/workspace/tasks${queryString ? `?${queryString}` : ''}`);
}

export type TaskStatus =
  'NEW' | 'IN_REVIEW' | 'APPROVED' | 'IN_PROGRESS' | 'SNOOZED' | 'DONE' | 'REJECTED' | 'CANCELLED';

/**
 * @param snoozedUntil required when status is 'SNOOZED' (Inbox rejects the change
 *   with 400 without it): when the task wakes up again. Ignored for other statuses.
 */
export function updateTaskStatus(
  taskId: string,
  status: TaskStatus,
  snoozedUntil?: Date,
): Promise<ApiTask> {
  return api.patch<ApiTask>(`/api/workspace/tasks/${taskId}/status`, {
    status,
    ...(snoozedUntil && { snoozedUntil: snoozedUntil.toISOString() }),
  });
}

/** Error code the backend returns when a status change is not allowed (e.g. moving a DONE task). */
export const INVALID_TASK_STATUS_TRANSITION = 'INVALID_TASK_STATUS_TRANSITION';
