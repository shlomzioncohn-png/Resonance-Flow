import { useQuery } from '@tanstack/react-query';
import { getDependencyGraph } from '@/services/bottlenecksApi';
import { bottleneckKeys } from '@/services/queryKeys';

/**
 * The task dependency graph, optionally for one project. Cached under
 * bottleneckKeys.graph(projectId) whatever the caller shows (the Bottlenecks
 * page or the Dashboard's compact view), so both share one request.
 */
export function useDependencyGraph(projectId?: string) {
  return useQuery({
    queryKey: bottleneckKeys.graph(projectId),
    queryFn: () => getDependencyGraph(projectId),
  });
}
