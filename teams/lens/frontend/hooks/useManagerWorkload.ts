import { useQuery } from '@tanstack/react-query';
import { getManagerWorkload } from '@/services/managerApi';
import { workloadKeys, type ManagerFilters } from '@/services/queryKeys';
import { MANAGER_LIVE_REFRESH_MS } from '@/constants';

/**
 * Team workload for the manager's Workload page. Comes through the Gateway
 * from the BFF (still mocked there until Inbox/Chain provide it).
 *
 * Polls every MANAGER_LIVE_REFRESH_MS (15s) so changes appear without a page refresh.
 * Background refetches don't show loading state (isPending stays false) and don't
 * clear the data on failure.
 *
 * Filters (if any) are included in the query key so changing them refetches immediately.
 * When no filters are provided, polls for the full workload.
 */
export function useManagerWorkload(filters?: ManagerFilters) {
  return useQuery({
    queryKey: workloadKeys.filtered(filters),
    queryFn: getManagerWorkload,
    refetchInterval: MANAGER_LIVE_REFRESH_MS,
    refetchIntervalInBackground: true,
  });
}
