'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import DependencyGraph from '@/components/ui/DependencyGraph';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import { useManagerKpis } from '@/hooks/useManagerKpis';
import { useProjectTaskCounts } from '@/hooks/useProjectTaskCounts';
import { getErrorMessage } from '@/services/api';
import { graphErrorMessage } from '@/services/bottlenecksApi';
import DashboardCharts from './DashboardCharts';
import ManagerKpiTiles from './ManagerKpiTiles';

export default function ManagerDashboardPage() {
  const router = useRouter();
  // Same cache entry as /manager/bottlenecks, so opening the full graph from here doesn't refetch.
  const graph = useDependencyGraph();
  const kpis = useManagerKpis();
  const projectCounts = useProjectTaskCounts();

  return (
    <PageContainer title="Dashboard" description="What needs your attention across the team.">
      <Stack spacing={3}>
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
            error={graph.isError ? graphErrorMessage(graph.error) : null}
            onRetry={() => graph.refetch()}
            onNodeSelect={(id) =>
              router.push(`/manager/bottlenecks?taskId=${encodeURIComponent(id)}`)
            }
          />
        </Card>
      </Stack>
    </PageContainer>
  );
}
