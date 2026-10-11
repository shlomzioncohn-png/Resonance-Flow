import { useQuery } from '@tanstack/react-query'
import { fetchManagerWorkload, fetchManagerWorkloadWithError } from '@/mocks/managerWorkload'
import { workloadKeys } from '@/services/queryKeys'

/** Team workload for the manager's Workload page (mock data until Inbox/Chain are wired in). */
export function useManagerWorkload({ simulateError }: { simulateError: boolean }) {
  return useQuery({
    // simulateError changes what queryFn returns, so it must be part of the key
    queryKey: [...workloadKeys.all, { simulateError }],
    queryFn: simulateError ? fetchManagerWorkloadWithError : fetchManagerWorkload,
    // Demo page: show the simulated error right away instead of retrying it
    retry: false,
    // Drop unused results at once, so recovering from the error shows Loading again, as before
    gcTime: 0,
  })
}
