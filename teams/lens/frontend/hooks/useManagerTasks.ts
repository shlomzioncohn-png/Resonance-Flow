import { useQuery } from '@tanstack/react-query';
import { getManagerTasks, type ManagerTaskView } from '@/services/managerApi';
import { kpiKeys } from '@/services/queryKeys';

/** The tenant's tasks for Work in progress or Blocked & delayed, from the BFF. */
export function useManagerTasks(view: ManagerTaskView) {
  return useQuery({
    queryKey: kpiKeys.tasks(view),
    queryFn: () => getManagerTasks(view),
  });
}
