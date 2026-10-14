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
