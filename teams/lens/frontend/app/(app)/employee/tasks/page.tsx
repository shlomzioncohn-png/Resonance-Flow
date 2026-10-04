'use client';

import { useCallback, useEffect, useState } from 'react';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import DataTable, { type DataTableColumn } from '@/components/ui/DataTable';
import { listTasks, type ApiTask } from '@/services/workspaceApi';
import { priorityTone, softToneSx, statusTone } from '../shared';

type LoadState = 'loading' | 'loaded' | 'error';

const columns: DataTableColumn<ApiTask>[] = [
  {
    key: 'title',
    header: 'Task',
    render: (task) => (
      <Stack spacing={0.25}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {task.title}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {task.project}
        </Typography>
      </Stack>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    render: (task) => (
      <Chip size="small" label={task.status} sx={softToneSx(statusTone(task.status))} />
    ),
  },
  {
    key: 'priority',
    header: 'Priority',
    render: (task) => (
      <Chip size="small" label={task.priority} sx={softToneSx(priorityTone(task.priority))} />
    ),
  },
  {
    key: 'owner',
    header: 'Owner',
    render: (task) => task.owner,
  },
  {
    key: 'due',
    header: 'Due',
    render: (task) => (task.dueDate ? new Date(task.dueDate).toLocaleString() : '—'),
  },
  {
    key: 'effort',
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
      {state === 'loading' && <LoadingState message="Loading your tasks…" />}

      {state === 'error' && (
        <ErrorState description="Couldn't load your tasks. Please try again." onRetry={retry} />
      )}

      {state === 'loaded' && (
        <DataTable
          columns={columns}
          rows={tasks}
          getRowId={(task) => task.id}
          getRowHref={(task) => `/employee/tasks/${task.id}`}
          emptyState={
            <EmptyState
              title="No tasks assigned"
              description="Tasks assigned to you will show up here."
            />
          }
        />
      )}
    </PageContainer>
  );
}
