/**
 * Every React Query key is defined here — pages never write key arrays by hand.
 * Keys are hierarchical, so invalidating a prefix refreshes everything under it:
 * taskKeys.all refreshes every task query, taskKeys.detail(id) only that one task.
 */
import type { WorkspaceRequest } from './workspaceApi'

export const taskKeys = {
  all: ['tasks'] as const,
  detail: (taskId: string) => [...taskKeys.all, 'detail', taskId] as const,
  recommendation: (request: WorkspaceRequest) =>
    [...taskKeys.all, 'recommendation', request] as const,
}

export const workloadKeys = {
  all: ['manager', 'workload'] as const,
}
