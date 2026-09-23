/**
 * Manager API — typed wrapper around the team-workload breakdown. No
 * Inbox/Chain-backed workload source exists yet, so it's mocked one layer
 * down, at the BFF (com.rnd.bff.client.ManagerClient) — this call is real,
 * straight through the Gateway, same as services/workspaceApi.ts.
 */
import { api } from './api'

export interface WorkloadEntry {
  employeeId: string
  employeeName: string
  activeTasks: number
}

/**
 * simulateError asks the mock handler to respond with a 500 instead of data
 * - see mocks/handlers.ts. Meaningless against a real backend; harmless
 * there too, since a real backend would just ignore an unknown query param.
 */
export function getManagerWorkload(simulateError = false): Promise<WorkloadEntry[]> {
  const query = simulateError ? '?simulateError=true' : ''
  return api.get<WorkloadEntry[]>(`/api/manager/workload${query}`)
}
