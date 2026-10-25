'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import Stack from '@mui/material/Stack';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import DependencyGraph from '@/components/ui/DependencyGraph';
import FilterChip from '@/components/ui/FilterChip';
import KpiTile from '@/components/ui/KpiTile';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import { getErrorMessage } from '@/services/api';
import { getProjects } from '@/services/projectsApi';
import { projectKeys } from '@/services/queryKeys';
import { MANAGER_TREND_REFRESH_MS } from '@/constants';
import TaskDependenciesDrawer from './TaskDependenciesDrawer';

const ALL_PROJECTS = 'all';
/** Same default as DependencyGraph: a task blocking this many is critical. */
const CRITICAL_THRESHOLD = 5;

export default function BottlenecksView() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const projectId = searchParams.get('projectId') ?? undefined;
  // The open task is in the URL too, so the Dashboard can link straight to it (?taskId=).
  const selectedId = searchParams.get('taskId');

  const graphQuery = useDependencyGraph(projectId);
  // Only feeds the filter's options: if it fails, the filter just offers "All projects".
  // Uses TREND refresh interval because project list changes less frequently than task graphs.
  const projectsQuery = useQuery({
    queryKey: projectKeys.trend(),
    queryFn: getProjects,
    refetchInterval: MANAGER_TREND_REFRESH_MS,
    refetchIntervalInBackground: true,
  });

  const projectOptions = [
    { label: 'All projects', value: ALL_PROJECTS },
    ...(projectsQuery.data ?? []).map((project) => ({ label: project.name, value: project.id })),
  ];
  // A project from a shared link still shows, even before (or without) the project list.
  if (projectId && !projectOptions.some((option) => option.value === projectId)) {
    projectOptions.push({ label: projectId, value: projectId });
  }

  // The filter and the open task live in the URL, so a view can be bookmarked and shared.
  const updateUrl = (changes: Record<string, string | null>) => {
    const params = new URLSearchParams(searchParams.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (value === null) params.delete(key);
      else params.set(key, value);
    }
    const query = params.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  };
  const setSelectedId = (id: string | null) => updateUrl({ taskId: id });
  const changeProject = (value: string) =>
    updateUrl({ projectId: value === ALL_PROJECTS ? null : value, taskId: null });

  const graph = graphQuery.data;
  const selected = graph?.nodes.find((node) => node.id === selectedId) ?? null;

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={1}>
        <FilterChip
          label="Project"
          options={projectOptions}
          value={projectId ?? ALL_PROJECTS}
          onChange={changeProject}
        />
      </Stack>

      {graphQuery.isPending && <LoadingState message="Loading dependency graph…" />}

      {graphQuery.isError && (
        <ErrorState description={getErrorMessage(graphQuery.error)} onRetry={() => graphQuery.refetch()} />
      )}

      {graph && graph.edges.length === 0 && (
        <EmptyState
          title="No dependencies"
          description={
            projectId
              ? 'No task in this project is waiting on another task right now.'
              : 'No task is waiting on another task right now.'
          }
        />
      )}

      {graph && graph.edges.length > 0 && (
        <>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            <KpiTile
              value={graph.nodes.filter((node) => node.blocksCount >= CRITICAL_THRESHOLD).length}
              label="Critical blockers"
              subtitle={`Blocking ${CRITICAL_THRESHOLD} or more tasks`}
              tone="critical"
            />
            <KpiTile
              value={graph.nodes.filter((node) => node.isBlocked).length}
              label="Blocked tasks"
              subtitle="Waiting on unfinished work"
              tone="warning"
            />
            <KpiTile value={graph.edges.length} label="Dependencies" tone="neutral" />
          </Stack>
          <DependencyGraph
            graph={graph}
            criticalThreshold={CRITICAL_THRESHOLD}
            onNodeSelect={setSelectedId}
            selectedNodeId={selectedId}
          />
        </>
      )}

      {graph && (
        <TaskDependenciesDrawer
          task={selected}
          graph={graph}
          onSelectTask={setSelectedId}
          onClose={() => setSelectedId(null)}
        />
      )}
    </Stack>
  );
}
