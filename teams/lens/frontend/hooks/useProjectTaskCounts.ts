import { useQuery } from '@tanstack/react-query';
import { getProjectTaskCounts } from '@/services/managerApi';
import { kpiKeys } from '@/services/queryKeys';

/** The Dashboard's charts: the tenant's tasks counted per project, from the BFF. */
export function useProjectTaskCounts() {
  return useQuery({
    queryKey: kpiKeys.projects(),
    queryFn: getProjectTaskCounts,
  });
}
