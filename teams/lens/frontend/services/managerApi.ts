/**
 * Manager API — typed wrapper around the team-workload breakdown. No
 * Inbox/Chain-backed workload source exists yet, so it's mocked one layer
 * down, at the BFF (com.rnd.bff.client.ManagerClient) — this call is real,
 * straight through the Gateway, same as services/workspaceApi.ts.
 */
import { api } from './api';

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
