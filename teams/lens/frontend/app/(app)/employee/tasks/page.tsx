'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import Chip from '@mui/material/Chip';
import MuiLink from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import DataTable, { type Column } from '@/components/ui/DataTable';
import { listTasks, type ApiTask } from '@/services/workspaceApi';
import { priorityTone, softToneSx, statusTone } from '../shared';

type LoadState = 'loading' | 'loaded' | 'error';

const columns: Column<ApiTask>[] = [
  {
    id: 'title',
    header: 'Task',
    render: (task) => (
      <Stack spacing={0.25}>
        <MuiLink
          component={Link}
          href={`/employee/tasks/${task.id}`}
          variant="body2"
          underline="hover"
          color="text.primary"
          sx={{ fontWeight: 600 }}
        >
          {task.title}
        </MuiLink>
        <Typography variant="caption" color="text.secondary">
          {task.project}
        </Typography>
      </Stack>
    ),
  },
  {
    id: 'status',
    header: 'Status',
    render: (task) => (
      <Chip size="small" label={task.status} sx={softToneSx(statusTone(task.status))} />
    ),
  },
  {
    id: 'priority',
    header: 'Priority',
    render: (task) => (
      <Chip size="small" label={task.priority} sx={softToneSx(priorityTone(task.priority))} />
    ),
  },
  {
    id: 'owner',
    header: 'Owner',
    render: (task) => task.owner,
  },
  {
    id: 'due',
    header: 'Due',
    render: (task) => (task.dueDate ? new Date(task.dueDate).toLocaleString() : '—'),
  },
  {
    id: 'effort',
    header: 'Effort',
    align: 'right',
    render: (task) => `${task.effortEstimate} min`,
  },
];

export default function EmployeeTasksPage() {
  const [state, setState] = useState<LoadState>('loading');
  const [tasks, setTasks] = useState<ApiTask[]>([]);

  const fetchTasks = useCallback(() => {
    return listTasks({ size: 100 }) // largest page the API allows — fine while the task set is this small; will need real pagination once it isn't
      .then((response) => {
        setTasks(response.items);
        setState('loaded');
      })
      .catch(() => setState('error'));
  }, []);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const retry = useCallback(() => {
    setState('loading');
    fetchTasks();
  }, [fetchTasks]);

  return (
    <PageContainer
      title="My Tasks"
      description="Your complete task list — status, due dates, and everything you own."
    >
      <DataTable
        ariaLabel="My tasks"
        columns={columns}
        rows={tasks}
        getRowId={(task) => task.id}
        loading={state === 'loading'}
        error={state === 'error' ? "Couldn't load your tasks. Please try again." : null}
        onRetry={retry}
        emptyTitle="No tasks assigned"
        emptyDescription="Tasks assigned to you will show up here."
      />
    </PageContainer>
  );
}
