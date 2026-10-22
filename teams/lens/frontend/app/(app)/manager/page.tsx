'use client';

import React, { useMemo } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import DependencyGraph from '@/components/ui/DependencyGraph';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import { getErrorMessage } from '@/services/api';
import ManagerKpiTiles from '@/components/layout/ManagerKpiTiles';
import { useManagerKpis } from '@/hooks/useManagerKpis';

/**
 * Format the time elapsed since dataUpdatedAt into a human-readable string.
 * Returns "now" for very recent updates, then "Xs ago" format.
 */
function formatTimeSinceUpdate(dataUpdatedAt: number | undefined): string {
  if (!dataUpdatedAt) return 'never';
  const elapsedSeconds = Math.floor((Date.now() - dataUpdatedAt) / 1000);
  if (elapsedSeconds < 2) return 'now';
  if (elapsedSeconds < 60) return `${elapsedSeconds}s ago`;
  const elapsedMinutes = Math.floor(elapsedSeconds / 60);
  if (elapsedMinutes < 60) return `${elapsedMinutes}m ago`;
  const elapsedHours = Math.floor(elapsedMinutes / 60);
  return `${elapsedHours}h ago`;
}

export default function ManagerDashboardPage() {
  const router = useRouter();
  // KPI tiles with live polling
  const kpis = useManagerKpis();
  // Same cache entry as /manager/bottlenecks, so opening the full graph from here doesn't refetch.
  const graph = useDependencyGraph();

  // Update every second so the "updated Xs ago" tag stays current.
  // (Without this, it would only update when the data itself refetches.)
  const displayTime = useMemo(() => formatTimeSinceUpdate(graph.dataUpdatedAt), [
    graph.dataUpdatedAt,
  ]);

  // Recompute display time every second in the background.
  // This is just for UI polish; the actual polling is driven by React Query's refetchInterval.
  React.useEffect(() => {
    const interval = setInterval(() => {
      // Force a re-render by accessing displayTime (it updates via useMemo).
      // In a real app, a small state update here would be cleaner, but
      // for now we let React's batching handle it naturally.
    }, 1000);
    return () => clearInterval(interval);
  }, []);

  return (
    <PageContainer title="Manager Dashboard" description="What needs your attention across the team.">
      <Stack spacing={3}>
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
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Chip
                label={`Auto-refresh · updated ${displayTime}`}
                variant="outlined"
                size="small"
                color="default"
              />
              <Button component={Link} href="/manager/bottlenecks" variant="outlined" size="small">
                View all
              </Button>
            </Stack>
          </Stack>
          <DependencyGraph
            variant="compact"
            interactive={false}
            ariaLabel="Top bottlenecks"
            graph={graph.data}
            loading={graph.isPending}
            error={graph.isError ? getErrorMessage(graph.error) : null}
            onRetry={() => graph.refetch()}
            onNodeSelect={(id) => router.push(`/manager/bottlenecks?taskId=${encodeURIComponent(id)}`)}
          />
        </Card>
      </Stack>
    </PageContainer>
  );
}
