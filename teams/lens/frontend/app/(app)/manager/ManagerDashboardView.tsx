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
import { useDashboardFilters } from '@/hooks/useDashboardFilters';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import { useManagerKpis } from '@/hooks/useManagerKpis';
import { getErrorMessage } from '@/services/api';
import { getProjects } from '@/services/projectsApi';
import { projectKeys } from '@/services/queryKeys';
import ManagerKpiTiles from './ManagerKpiTiles';

/** No API returns the tenant's display name yet. */
const ORG_NAME = 'Your organization';

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
  // Same cache entry as /manager/bottlenecks, so opening the full graph from here doesn't refetch.
  const graph = useDependencyGraph();
  // The KPI endpoint takes a single project, so it narrows to one only when exactly one is selected.
  const kpis = useManagerKpis({
    period: filters.period,
    project: filters.projectIds?.length === 1 ? filters.projectIds[0] : undefined,
  });
  // Only feeds the Project chip's options: if it fails, the chip just offers nothing to add.
  const projectsQuery = useQuery({ queryKey: projectKeys.all, queryFn: getProjects });
  const updatedSecondsAgo = useSecondsSince(Math.max(kpis.dataUpdatedAt, graph.dataUpdatedAt));

  return (
    <Stack spacing={3}>
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
          <Button component={Link} href="/manager/bottlenecks" variant="outlined" size="small">
            View all
          </Button>
        </Stack>
        <DependencyGraph
          variant="compact"
          interactive={false}
          ariaLabel="Top bottlenecks"
          graph={graph.data}
          loading={graph.isPending}
          error={graph.isError ? getErrorMessage(graph.error) : null}
          onRetry={() => graph.refetch()}
          onNodeSelect={(id) =>
            router.push(`/manager/bottlenecks?taskId=${encodeURIComponent(id)}`)
          }
        />
      </Card>
    </Stack>
  );
}
