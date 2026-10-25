'use client';

import Stack from '@mui/material/Stack';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import KpiTile from '@/components/ui/KpiTile';
import type { ManagerKpis } from '@/services/managerApi';

type ManagerKpiTilesProps = {
  kpis?: ManagerKpis;
  loading?: boolean;
  /** Shown instead of the tiles, with a Retry button. */
  error?: string | null;
  onRetry?: () => void;
};

/** Days with one decimal, e.g. 3.2 → "3.2d". */
function formatDays(days: number): string {
  return `${days.toFixed(1)}d`;
}

/** The Manager Dashboard's four KPI tiles, for the manager's whole tenant. */
export default function ManagerKpiTiles({
  kpis,
  loading = false,
  error,
  onRetry,
}: ManagerKpiTilesProps) {
  if (loading) return <LoadingState message="Loading KPIs…" />;
  if (error || !kpis) {
    return (
      <ErrorState
        title="KPIs could not be loaded"
        description={error ?? undefined}
        onRetry={onRetry}
        retryLabel="Retry"
      />
    );
  }

  const avgKnown = kpis.avgBlockedDays !== null;

  return (
    <Stack
      component="section"
      aria-label="Key indicators"
      direction={{ xs: 'column', md: 'row' }}
      spacing={2}
    >
      <KpiTile value={kpis.inProgress} label="In progress" tone="neutral" />
      <KpiTile value={kpis.blocked} label="Blocked" tone="critical" />
      <KpiTile value={kpis.delayed} label="Delayed" tone="warning" />
      <KpiTile
        value={avgKnown ? formatDays(kpis.avgBlockedDays as number) : '—'}
        label="Avg. blocked time"
        tone="neutral"
        subtitle={avgKnown ? undefined : 'Not available yet'}
      />
    </Stack>
  );
}
