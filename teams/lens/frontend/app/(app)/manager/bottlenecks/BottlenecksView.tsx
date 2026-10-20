'use client';

import { useState } from 'react';
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
import TaskDependenciesDrawer from './TaskDependenciesDrawer';

const ALL_PROJECTS = 'all';
/** Same default as DependencyGraph: a task blocking this many is critical. */
const CRITICAL_THRESHOLD = 5;

export default function BottlenecksView() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const projectId = searchParams.get('projectId') ?? undefined;
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const graphQuery = useDependencyGraph(projectId);
  // Only feeds the filter's options: if it fails, the filter just offers "All projects".
  const projectsQuery = useQuery({ queryKey: projectKeys.all, queryFn: getProjects });

  const projectOptions = [
    { label: 'All projects', value: ALL_PROJECTS },
    ...(projectsQuery.data ?? []).map((project) => ({ label: project.name, value: project.id })),
  ];
  // A project from a shared link still shows, even before (or without) the project list.
  if (projectId && !projectOptions.some((option) => option.value === projectId)) {
    projectOptions.push({ label: projectId, value: projectId });
  }

  // The filter lives in the URL, so a filtered view can be bookmarked and shared.
  const changeProject = (value: string) => {
    const params = new URLSearchParams(searchParams.toString());
    if (value === ALL_PROJECTS) params.delete('projectId');
    else params.set('projectId', value);
    const query = params.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
    setSelectedId(null);
  };

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
