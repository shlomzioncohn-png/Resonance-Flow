import { useQuery } from '@tanstack/react-query';
import { taskKeys } from '@/services/queryKeys';
import { getTaskActivity } from '@/services/workspaceApi';

/**
 * One task's history (Chain, merged newest first by the BFF), cached under
 * taskKeys.activity(taskId). `enabled: false` waits until the Activity tab is opened.
 */
export function useTaskActivity(taskId: string, enabled = true) {
  return useQuery({
    queryKey: taskKeys.activity(taskId),
    queryFn: () => getTaskActivity(taskId),
    enabled,
  });
}
