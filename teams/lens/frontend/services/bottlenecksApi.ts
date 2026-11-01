/**
 * Bottlenecks API — typed wrapper around the Gateway's task dependency graph
 * for the manager's Bottlenecks screen. Real data: the BFF reads Chain's
 * graph for the manager's tenant (GET /tenants/{tenantId}/dependency-graph)
 * and adds each task's title from Inbox. Same shape as the Gateway's and BFF's
 * DependencyGraphDTO - don't change a field here without them.
 */
import { api, ApiError, getErrorMessage } from './api';

/** Same values as Chain's TaskStatus. */
export type GraphTaskStatus = 'PENDING' | 'ACTIVE' | 'DONE';

export type GraphNode = {
  id: string;
  title: string;
  status: GraphTaskStatus;
  /** True when Chain says the task isn't ready: a task it depends on isn't done yet, or it has an open constraint. */
  isBlocked: boolean;
  /** How many tasks depend on this one, directly or indirectly. */
  blocksCount: number;
};

/** `source` blocks `target`: the edge points from the blocker to the task it blocks. */
export type GraphEdge = {
  source: string;
  target: string;
  /** 0-1: how sure Chain is that this dependency is real. */
  confidence: number;
};

export type DependencyGraph = {
  nodes: GraphNode[];
  edges: GraphEdge[];
};

/** The whole graph, or only one project's tasks when `projectId` is given. */
export function getDependencyGraph(projectId?: string): Promise<DependencyGraph> {
  const query = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return api.get<DependencyGraph>(`/api/manager/bottlenecks/graph${query}`);
}

/**
 * The message for a failed graph request. A 404 means the Gateway in use is
 * older than the graph endpoint - the generic 404 text talks about "this
 * task", which would be wrong here.
 */
export function graphErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return "The dependency graph isn't available yet.";
  }
  return getErrorMessage(error);
}
