import { useQuery } from '@tanstack/react-query';
import { getProjects } from '@/services/projectsApi';
import { projectKeys, type ManagerFilters } from '@/services/queryKeys';
import { MANAGER_LIVE_REFRESH_MS } from '@/constants';

/**
 * All projects with health/KPI data for the manager's Projects page.
 * Comes through the Gateway from the BFF.
 *
 * Polls every MANAGER_LIVE_REFRESH_MS (15s) so project health changes appear
 * without a page refresh. Background refetches don't show loading state and
 * don't clear the data on failure.
 *
 * Filters (if any) are included in the query key so changing them refetches
 * with the new filter criteria. When no filters are provided, returns all projects.
 *
 * Replaces the useState + useEffect pattern that was manually managing the fetch
 * and loading state. Now React Query handles caching, retries, and polling.
 */
export function useManagerProjects(filters?: ManagerFilters) {
  return useQuery({
    queryKey: projectKeys.filtered(filters),
    queryFn: getProjects,
    refetchInterval: MANAGER_LIVE_REFRESH_MS,
    refetchIntervalInBackground: true,
  });
}
