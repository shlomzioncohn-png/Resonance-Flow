'use client';

import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { BarChart } from '@mui/x-charts/BarChart';
import { PieChart } from '@mui/x-charts/PieChart';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import type { ProjectTaskCounts } from '@/services/managerApi';
import { chartTokens } from '@/theme';

type DashboardChartsProps = {
  projects?: ProjectTaskCounts[];
  loading?: boolean;
  /** Shown instead of the charts, with a Retry button. */
  error?: string | null;
  onRetry?: () => void;
};

/** The task states, in stack and legend order. Colors come from chartTokens. */
const STATES = [
  { key: 'inProgress', label: 'In progress', color: chartTokens.inProgress },
  { key: 'snoozed', label: 'Snoozed', color: chartTokens.snoozed },
  { key: 'open', label: 'Open', color: chartTokens.open },
  { key: 'done', label: 'Done', color: chartTokens.done },
] as const;

/** Hidden on screen, read by screen readers - the charts' numbers as tables. */
const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const;

function ChartCard({
  id,
  title,
  description,
  children,
}: {
  id: string;
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <Card component="section" aria-labelledby={id} sx={{ p: 3, flex: 1, minWidth: 0 }}>
      <Typography id={id} variant="h6" component="h2">
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {description}
      </Typography>
      {children}
    </Card>
  );
}

/**
 * The Dashboard's two charts: all tasks by state (donut), and the same states
 * per project (stacked bars). Each has a table for screen readers.
 */
export default function DashboardCharts({
  projects,
  loading = false,
  error,
  onRetry,
}: DashboardChartsProps) {
  if (loading) return <LoadingState message="Loading charts…" />;
  if (error || !projects) {
    return (
      <ErrorState
        title="Charts could not be loaded"
        description={error ?? undefined}
        onRetry={onRetry}
        retryLabel="Retry"
      />
    );
  }
  if (projects.length === 0) {
    return <EmptyState title="No tasks yet" description="The charts fill in once your team has tasks." />;
  }

  const totals = STATES.map((state) => ({
    ...state,
    value: projects.reduce((sum, project) => sum + project[state.key], 0),
  }));
  const totalTasks = totals.reduce((sum, state) => sum + state.value, 0);
  const totalOverdue = projects.reduce((sum, project) => sum + project.overdue, 0);

  return (
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3}>
      <ChartCard
        id="dashboard-status-title"
        title="Tasks by status"
        description={`${totalOverdue} of ${totalTasks} tasks are past their deadline.`}
      >
        <Box sx={{ position: 'relative' }}>
          <PieChart
            height={260}
            series={[
              {
                data: totals.map((state) => ({
                  id: state.key,
                  value: state.value,
                  label: state.label,
                  color: state.color,
                })),
                innerRadius: 70,
                outerRadius: 110,
                paddingAngle: 1,
                cornerRadius: 4,
                highlightScope: { fade: 'global', highlight: 'item' },
              },
            ]}
            slotProps={{ legend: { direction: 'horizontal', position: { vertical: 'bottom' } } }}
          />
          {/* The total in the hole of the donut */}
          <Box
            aria-hidden
            sx={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              height: 220,
              display: 'grid',
              placeItems: 'center',
              pointerEvents: 'none',
            }}
          >
            <Box sx={{ textAlign: 'center' }}>
              <Typography variant="h4" component="p">
                {totalTasks}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                tasks
              </Typography>
            </Box>
          </Box>
        </Box>
        <Box component="table" sx={visuallyHidden}>
          <caption>Tasks by status</caption>
          <tbody>
            {totals.map((state) => (
              <tr key={state.key}>
                <th scope="row">{state.label}</th>
                <td>{state.value}</td>
              </tr>
            ))}
          </tbody>
        </Box>
      </ChartCard>

      <ChartCard
        id="dashboard-projects-title"
        title="Tasks per project"
        description="Where each project's work stands."
      >
        <BarChart
          height={320}
          layout="horizontal"
          dataset={projects}
          yAxis={[
            {
              scaleType: 'band',
              dataKey: 'project',
              width: 120,
              tickLabelStyle: { fontSize: 12 },
            },
          ]}
          xAxis={[
            {
              label: 'Tasks',
              // Room for the numbers and the "Tasks" label - too little, and the
              // chart cuts the numbers down to nothing
              height: 50,
              tickMinStep: 5,
              tickLabelStyle: { fontSize: 12 },
            },
          ]}
          series={STATES.map((state) => ({
            dataKey: state.key,
            label: state.label,
            color: state.color,
            stack: 'tasks',
          }))}
          borderRadius={4}
          grid={{ vertical: true }}
          slotProps={{ legend: { direction: 'horizontal', position: { vertical: 'bottom' } } }}
        />
        <Box component="table" sx={visuallyHidden}>
          <caption>Tasks per project</caption>
          <thead>
            <tr>
              <th scope="col">Project</th>
              {STATES.map((state) => (
                <th key={state.key} scope="col">
                  {state.label}
                </th>
              ))}
              <th scope="col">Overdue</th>
            </tr>
          </thead>
          <tbody>
            {projects.map((project) => (
              <tr key={project.project}>
                <th scope="row">{project.project}</th>
                {STATES.map((state) => (
                  <td key={state.key}>{project[state.key]}</td>
                ))}
                <td>{project.overdue}</td>
              </tr>
            ))}
          </tbody>
        </Box>
      </ChartCard>
    </Stack>
  );
}
