import { useQuery } from '@tanstack/react-query';
import { taskKeys } from '@/services/queryKeys';
import { listTasks } from '@/services/workspaceApi';

/** The most tasks the API returns in one page. */
const MAX_PAGE_SIZE = 100;

/**
 * The names of the projects the employee works on, A-Z - taken from their
 * own tasks, because Inbox has no "projects this user belongs to" list.
 * Tasks without a project are skipped.
 */
export function useMyProjects() {
  return useQuery({
    queryKey: taskKeys.myProjects(),
    queryFn: async () => {
      const { items } = await listTasks({ size: MAX_PAGE_SIZE });
      const names = items.map((task) => task.project).filter((name): name is string => !!name);
      return Array.from(new Set(names)).sort((a, b) => a.localeCompare(b));
    },
  });
}
