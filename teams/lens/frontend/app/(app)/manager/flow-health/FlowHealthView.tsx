'use client';

import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import ButtonBase from '@mui/material/ButtonBase';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import DataTable from '@/components/ui/DataTable';
import type { Column } from '@/components/ui/DataTable/types';
import FilterChip from '@/components/ui/FilterChip';
import KpiTile from '@/components/ui/KpiTile';
import StatusBadge from '@/components/ui/StatusBadge';
import { useFlowHealth } from '@/hooks/useFlowHealth';
import { getErrorMessage } from '@/services/api';
import type { FlowFlag } from '@/services/flowHealthApi';
import FlowFlagDrawer from './FlowFlagDrawer';
import {
  entityLabel,
  filterFlags,
  formatRunDate,
  parseOneOf,
  PATTERN_LABEL,
  PATTERNS,
  SEVERITIES,
  SEVERITY_LABEL,
  SEVERITY_TONE,
} from './flowHealthModel';

const ALL = 'all';

export default function FlowHealthView() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  // The filters and the open flag live in the URL, so a view can be bookmarked and shared.
  const pattern = parseOneOf(searchParams.get('pattern'), PATTERNS);
  const severity = parseOneOf(searchParams.get('severity'), SEVERITIES);
  const projectId = searchParams.get('projectId') ?? undefined;
  const selectedId = searchParams.get('flagId');

  const query = useFlowHealth();

  const updateUrl = (changes: Record<string, string | null>) => {
    const params = new URLSearchParams(searchParams.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (value === null || value === ALL) params.delete(key);
      else params.set(key, value);
    }
    const next = params.toString();
    router.replace(next ? `${pathname}?${next}` : pathname, { scroll: false });
  };
  const openFlag = (flag: FlowFlag) => updateUrl({ flagId: flag.flagId });

  if (query.isPending) return <LoadingState message="Loading flow health…" />;
  if (query.isError) {
    return <ErrorState description={getErrorMessage(query.error)} onRetry={() => query.refetch()} />;
  }

  const health = query.data;
  const flags = filterFlags(health.flags, { pattern, severity, projectId });
  const selected = health.flags.find((flag) => flag.flagId === selectedId) ?? null;
  const lastRun = formatRunDate(health.lastRunAt);

  const projectOptions = [
    { label: 'All projects', value: ALL },
    ...health.projects.map((project) => ({ label: project.name, value: project.id })),
  ];

  const columns: Column<FlowFlag>[] = [
    { id: 'rank', header: 'Rank', width: 70, render: (flag) => flag.rank },
    { id: 'pattern', header: 'Pattern', render: (flag) => PATTERN_LABEL[flag.pattern] },
    {
      id: 'entity',
      header: 'Entity',
      render: (flag) => {
        const label = entityLabel(flag);
        // The row click is a mouse shortcut; this button is the keyboard and screen-reader way in.
        return (
          <ButtonBase
            onClick={(event) => {
              event.stopPropagation();
              openFlag(flag);
            }}
            aria-label={`Open details: ${label}`}
            sx={{ textAlign: 'left', textDecoration: 'underline', overflowWrap: 'anywhere' }}
          >
            {label}
          </ButtonBase>
        );
      },
    },
    {
      id: 'severity',
      header: 'Severity',
      render: (flag) => <StatusBadge status={SEVERITY_TONE[flag.severity]} label={SEVERITY_LABEL[flag.severity]} />,
    },
    { id: 'explanation', header: 'Explanation', render: (flag) => flag.explanation },
  ];

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
        <KpiTile value={health.counts.CHRONIC_BLOCKER} label="Chronic blockers" tone="critical" />
        <KpiTile value={health.counts.RECURRING_CYCLE} label="Blocker cycles" tone="warning" />
        <KpiTile value={health.counts.SLOW_CHAIN} label="Slow chains" tone="warning" />
      </Stack>
      {lastRun && (
        <Typography variant="body2" color="text.secondary">
          Last run: {lastRun}
        </Typography>
      )}

      {health.flags.length === 0 ? (
        <EmptyState title="אין עדיין מספיק היסטוריה" />
      ) : (
        <>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            <FilterChip
              label="Pattern"
              options={[
                { label: 'All patterns', value: ALL },
                ...PATTERNS.map((value) => ({ label: PATTERN_LABEL[value], value })),
              ]}
              value={pattern ?? ALL}
              onChange={(value) => updateUrl({ pattern: value, flagId: null })}
            />
            <FilterChip
              label="Severity"
              options={[
                { label: 'All severities', value: ALL },
                ...SEVERITIES.map((value) => ({ label: SEVERITY_LABEL[value], value })),
              ]}
              value={severity ?? ALL}
              onChange={(value) => updateUrl({ severity: value, flagId: null })}
            />
            <FilterChip
              label="Project"
              options={projectOptions}
              value={projectId ?? ALL}
              onChange={(value) => updateUrl({ projectId: value, flagId: null })}
            />
          </Stack>
          <DataTable
            ariaLabel="Flow-health flags"
            columns={columns}
            rows={flags}
            getRowId={(flag) => flag.flagId}
            onRowClick={openFlag}
            emptyTitle="No flags match these filters"
            emptyDescription="Try a different pattern, severity or project."
          />
        </>
      )}

      <FlowFlagDrawer flag={selected} onClose={() => updateUrl({ flagId: null })} />
    </Stack>
  );
}
