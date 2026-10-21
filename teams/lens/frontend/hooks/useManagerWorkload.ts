import { useQuery } from '@tanstack/react-query';
import { getManagerWorkload } from '@/services/managerApi';
import { workloadKeys } from '@/services/queryKeys';

/**
 * Team workload for the manager's Workload page. Comes through the Gateway
 * from the BFF (still mocked there until Inbox/Chain provide it). Retries
 * follow the QueryProvider defaults.
 */
export function useManagerWorkload() {
  return useQuery({
    queryKey: workloadKeys.all,
    queryFn: getManagerWorkload,
  });
}
