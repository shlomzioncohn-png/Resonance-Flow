/**
 * Every React Query key is defined here — pages never write key arrays by hand.
 * Keys are hierarchical, so invalidating a prefix refreshes everything under it:
 * taskKeys.all refreshes every task query, taskKeys.detail(id) only that one task.
 */
import type { WorkspaceRequest } from './workspaceApi';

export const taskKeys = {
  all: ['tasks'] as const,
  detail: (taskId: string) => [...taskKeys.all, 'detail', taskId] as const,
  recommendation: (request: WorkspaceRequest) =>
    [...taskKeys.all, 'recommendation', request] as const,
};

export const workloadKeys = {
  all: ['manager', 'workload'] as const,
};

export const projectKeys = {
  all: ['manager', 'projects'] as const,
};

/**
 * Not keyed by how the graph is shown (full page or the Dashboard's compact
 * view): both read the same cache entry, so moving between them doesn't refetch.
 */
export const bottleneckKeys = {
  all: ['manager', 'bottlenecks'] as const,
  graph: (projectId?: string) => [...bottleneckKeys.all, 'graph', projectId ?? 'all'] as const,
};
