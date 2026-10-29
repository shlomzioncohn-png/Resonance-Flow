'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import DashboardHeader from '@/components/layout/DashboardHeader';
import DependencyGraph from '@/components/ui/DependencyGraph';
import WorkInsights from '@/components/ui/WorkInsights';
import { useDashboardFilters } from '@/hooks/useDashboardFilters';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import { useManagerKpis } from '@/hooks/useManagerKpis';
import { useProjectTaskCounts } from '@/hooks/useProjectTaskCounts';
import { getErrorMessage } from '@/services/api';
import { graphErrorMessage } from '@/services/bottlenecksApi';
import { getProjects } from '@/services/projectsApi';
import { projectKeys } from '@/services/queryKeys';
import DashboardCharts from './DashboardCharts';
import ManagerKpiTiles from './ManagerKpiTiles';

/** No API returns the tenant's display name yet. */
const ORG_NAME = 'Your organization';

/** The full Bottlenecks screen, on the same project as the dashboard and optionally with a task open. */
function bottlenecksHref(projectId?: string, taskId?: string) {
  const params = new URLSearchParams();
  if (projectId) params.set('projectId', projectId);
  if (taskId) params.set('taskId', taskId);
  const query = params.toString();
  return query ? `/manager/bottlenecks?${query}` : '/manager/bottlenecks';
}

/** Seconds since `timestamp`, ticking once a second. */
function useSecondsSince(timestamp: number) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  return timestamp ? Math.max(0, Math.floor((now - timestamp) / 1000)) : 0;
}

export default function ManagerDashboardView() {
  const router = useRouter();
  const { filters, applyFilters } = useDashboardFilters();
  // The dashboard endpoints filter by a single project, so the header lets the manager pick one.
  const projectId = filters.projectIds?.[0];
  // Same cache entry as /manager/bottlenecks, so opening the full graph from here doesn't refetch.
  // It has no period: Chain's graph is always "right now".
  const graph = useDependencyGraph(projectId);
  const kpis = useManagerKpis({ period: filters.period, project: projectId });
  // The charts always show every project - they compare projects with each other.
  const projectCounts = useProjectTaskCounts();
  // Only feeds the Project chip's options: if it fails, the chip just offers no projects.
  const projectsQuery = useQuery({ queryKey: projectKeys.all, queryFn: getProjects });
  const updatedSecondsAgo = useSecondsSince(Math.max(kpis.dataUpdatedAt, graph.dataUpdatedAt));

  return (
    <Stack spacing={3}>
      <WorkInsights />
      <DashboardHeader
        orgName={ORG_NAME}
        filters={filters}
        onApplyFilters={applyFilters}
        projects={(projectsQuery.data ?? []).map(({ id, name }) => ({ id, name }))}
        updatedSecondsAgo={updatedSecondsAgo}
      />

      <ManagerKpiTiles
        kpis={kpis.data}
        loading={kpis.isPending}
        error={kpis.isError ? getErrorMessage(kpis.error) : null}
        onRetry={() => kpis.refetch()}
      />

      <DashboardCharts
        projects={projectCounts.data}
        loading={projectCounts.isPending}
        error={projectCounts.isError ? getErrorMessage(projectCounts.error) : null}
        onRetry={() => projectCounts.refetch()}
      />

      <Card component="section" aria-labelledby="dashboard-bottlenecks-title" sx={{ p: 3 }}>
        <Stack
          direction="row"
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: 'flex-start', mb: 2 }}
        >
          <div>
            <Typography id="dashboard-bottlenecks-title" variant="h6" component="h2">
              Bottlenecks
            </Typography>
            <Typography variant="body2" color="text.secondary">
              The tasks holding up the most work. Click one to see what it blocks.
            </Typography>
          </div>
          <Button
            component={Link}
            href={bottlenecksHref(projectId)}
            variant="outlined"
            size="small"
          >
            View all
          </Button>
        </Stack>
        <DependencyGraph
          variant="compact"
          interactive={false}
          ariaLabel="Top bottlenecks"
          graph={graph.data}
          loading={graph.isPending}
          error={graph.isError ? graphErrorMessage(graph.error) : null}
          onRetry={() => graph.refetch()}
          onNodeSelect={(id) => router.push(bottlenecksHref(projectId, id))}
        />
      </Card>
    </Stack>
  );
}
