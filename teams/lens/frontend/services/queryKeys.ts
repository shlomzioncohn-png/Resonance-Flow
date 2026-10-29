/**
 * Every React Query key is defined here — pages never write key arrays by hand.
 * Keys are hierarchical, so invalidating a prefix refreshes everything under it:
 * taskKeys.all refreshes every task query, taskKeys.detail(id) only that one task.
 * 
 * All manager queries include relevant filters (period, team, project) in their keys,
 * so that changing filters triggers a refetch with new data.
 */
import type { ManagerKpiFilters, ManagerTaskView } from './managerApi';
import type { WorkspaceRequest } from './workspaceApi';

export const taskKeys = {
  all: ['tasks'] as const,
  detail: (taskId: string) => [...taskKeys.all, 'detail', taskId] as const,
  activity: (taskId: string) => [...taskKeys.all, 'activity', taskId] as const,
  recommendation: (request: WorkspaceRequest) =>
    [...taskKeys.all, 'recommendation', request] as const,
  /** The projects of the signed-in employee's own tasks (My Workspace's Project filter). */
  myProjects: () => [...taskKeys.all, 'my-projects'] as const,
};

export interface ManagerFilters {
  period?: string;
  team?: string;
  project?: string;
}

export const kpiKeys = {
  all: ['manager', 'kpis'] as const,
  list: (filters: ManagerKpiFilters) => [...kpiKeys.all, filters] as const,
  /** The Dashboard's charts: task counts per project */
  projects: () => [...kpiKeys.all, 'projects'] as const,
  /** Work in progress / Blocked & delayed */
  tasks: (view: ManagerTaskView) => [...kpiKeys.all, 'tasks', view] as const,
};

export const projectKeys = {
  all: ['manager', 'projects'] as const,
  filtered: (filters?: ManagerFilters) =>
    [...projectKeys.all, filters ? { ...filters } : {}] as const,
  // Trend data (e.g., "Flow over time")
  trend: () => [...projectKeys.all, 'trend'] as const,
};

/**
 * Not keyed by how the graph is shown (full page or the Dashboard's compact
 * view): both read the same cache entry, so moving between them doesn't refetch.
 * Includes projectId filter when querying a subset.
 */
export const bottleneckKeys = {
  all: ['manager', 'bottlenecks'] as const,
  graph: (projectId?: string) => [...bottleneckKeys.all, 'graph', projectId ?? 'all'] as const,
};
