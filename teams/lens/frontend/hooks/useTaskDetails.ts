import { useQuery } from '@tanstack/react-query';
import { taskKeys } from '@/services/queryKeys';
import { getTask } from '@/services/workspaceApi';

/** One task's details, cached under taskKeys.detail(taskId). */
export function useTaskDetails(taskId: string) {
  return useQuery({
    queryKey: taskKeys.detail(taskId),
    queryFn: () => getTask(taskId),
  });
}
