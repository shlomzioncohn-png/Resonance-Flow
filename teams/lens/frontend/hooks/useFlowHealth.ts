import { useQuery } from '@tanstack/react-query';
import { getFlowHealth } from '@/services/flowHealthApi';
import { flowHealthKeys } from '@/services/queryKeys';
import { MANAGER_TREND_REFRESH_MS } from '@/constants';

/**
 * The flow-health flags. The analytics job runs periodically, not live, so this
 * refreshes at the slow TREND interval; background refetches keep the data on failure.
 */
export function useFlowHealth() {
  return useQuery({
    queryKey: flowHealthKeys.all,
    queryFn: getFlowHealth,
    refetchInterval: MANAGER_TREND_REFRESH_MS,
    refetchIntervalInBackground: true,
  });
}
