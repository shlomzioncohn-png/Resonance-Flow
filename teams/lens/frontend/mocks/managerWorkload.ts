export interface WorkloadEntry {
  employeeId: string
  employeeName: string
  activeTasks: number
}

const SIMULATED_DELAY_MS = 700

/**
 * Stand-in for the future Inbox/Chain-backed workload adapter. Always resolves
 * to an empty list — there is no real workload data source wired up yet.
 */
export async function fetchManagerWorkload(): Promise<WorkloadEntry[]> {
  await new Promise((resolve) => setTimeout(resolve, SIMULATED_DELAY_MS))
  return []
}

export async function fetchManagerWorkloadWithError(): Promise<WorkloadEntry[]> {
  await new Promise((resolve) => setTimeout(resolve, SIMULATED_DELAY_MS))
  throw new Error('Simulated failure — demonstrates the ErrorState.')
}
