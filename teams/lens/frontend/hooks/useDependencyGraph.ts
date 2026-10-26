import { useQuery } from '@tanstack/react-query';
import { getDependencyGraph } from '@/services/bottlenecksApi';
import { bottleneckKeys } from '@/services/queryKeys';
import { MANAGER_LIVE_REFRESH_MS } from '@/constants';

/**
 * The task dependency graph, optionally for one project. Cached under
 * bottleneckKeys.graph(projectId) whatever the caller shows (the Bottlenecks
 * page or the Dashboard's compact view), so both share one request.
 *
 * Polls every MANAGER_LIVE_REFRESH_MS (15s) so new blockers and blocked tasks
 * appear without a page refresh. Background refetches don't show loading state
 * and don't clear the data on failure.
 */
export function useDependencyGraph(projectId?: string) {
  return useQuery({
    queryKey: bottleneckKeys.graph(projectId),
    queryFn: () => getDependencyGraph(projectId),
    refetchInterval: MANAGER_LIVE_REFRESH_MS,
    refetchIntervalInBackground: true,
  });
}
