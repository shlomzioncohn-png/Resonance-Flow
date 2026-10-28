/**
 * Manager API — typed wrapper around the team-workload breakdown. No
 * Inbox/Chain-backed workload source exists yet, so it's mocked one layer
 * down, at the BFF (com.rnd.bff.client.ManagerClient) — this call is real,
 * straight through the Gateway, same as services/workspaceApi.ts.
 */
import { api } from './api';
import type { ApiTask } from './workspaceApi';

export interface WorkloadEntry {
  employeeId: string;
  employeeName: string;
  activeTasks: number;
}

export function getManagerWorkload(): Promise<WorkloadEntry[]> {
  return api.get<WorkloadEntry[]>('/api/manager/workload');
}

/**
 * The Manager Dashboard's KPI tiles, for the manager's whole tenant. Real data:
 * the BFF counts the tenant's Inbox tasks. The tenant is never sent - the
 * server takes it from the signed-in manager.
 */
export type ManagerKpis = {
  inProgress: number;
  blocked: number;
  delayed: number;
  /** null until Chain provides how long tasks stay blocked. */
  avgBlockedDays: number | null;
};

export type ManagerKpiFilters = {
  /** Accepted by the BFF, not applied yet - every KPI is a count of right now. */
  period?: '7d' | '30d' | '90d';
  /** An Inbox project id; omit for the whole tenant. */
  project?: string;
};

export function getManagerKpis(filters: ManagerKpiFilters = {}): Promise<ManagerKpis> {
  const query = new URLSearchParams();
  if (filters.period) query.set('period', filters.period);
  if (filters.project) query.set('project', filters.project);
  const queryString = query.toString();
  return api.get<ManagerKpis>(`/api/manager/dashboard/kpis${queryString ? `?${queryString}` : ''}`);
}

/**
 * One project's tasks counted by state, for the Dashboard's charts. The BFF
 * counts them from the same Inbox tasks as the KPI tiles. REJECTED and
 * CANCELLED tasks aren't counted.
 */
export type ProjectTaskCounts = {
  /** null for the "No project" row */
  projectId: string | null;
  project: string;
  inProgress: number;
  snoozed: number;
  /** Not started yet: NEW, IN_REVIEW or APPROVED */
  open: number;
  done: number;
  /** Not done and past the deadline - part of the counts above, not extra */
  overdue: number;
};

/** Every project in the manager's tenant, A-Z. */
export function getProjectTaskCounts(): Promise<ProjectTaskCounts[]> {
  return api.get<ProjectTaskCounts[]>('/api/manager/dashboard/projects');
}

/**
 * The lists behind the manager's task screens, counted by the BFF the same
 * way as the KPI tiles: in-progress = status IN_PROGRESS; blocked-delayed =
 * open tasks that are blocked or past their deadline.
 */
export type ManagerTaskView = 'in-progress' | 'blocked-delayed';

/** The tenant's tasks for one screen, soonest deadline first. */
export function getManagerTasks(view: ManagerTaskView): Promise<ApiTask[]> {
  return api.get<ApiTask[]>(`/api/manager/dashboard/tasks/${view}`);
}
