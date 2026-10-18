'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import Chip from '@mui/material/Chip';
import MuiLink from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import DataTable, { type Column } from '@/components/ui/DataTable';
import { getErrorMessage } from '@/services/api';
import { listTasks, type ApiTask } from '@/services/workspaceApi';
import { PriorityChip, softToneSx, statusTone } from '../shared';

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
        {task.project && (
          <Typography variant="caption" color="text.secondary">
            {task.project}
          </Typography>
        )}
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
    render: (task) => <PriorityChip priority={task.priority} />,
  },
  {
    id: 'owner',
    header: 'Owner',
    render: (task) => task.owner ?? '—',
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
    render: (task) => (task.effortEstimate != null ? `${task.effortEstimate} min` : '—'),
  },
];

export default function EmployeeTasksPage() {
  const [state, setState] = useState<LoadState>('loading');
  const [tasks, setTasks] = useState<ApiTask[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [errorMessage, setErrorMessage] = useState('');

  // One page at a time from the server (Inbox does the paging). Only sets
  // state once the request settles, so it's safe to call from an effect.
  const fetchTasks = useCallback((pageIndex: number, size: number) => {
    return listTasks({ page: pageIndex, size })
      .then((response) => {
        setTasks(response.items);
        setTotal(response.total);
        setState('loaded');
      })
      .catch((err) => {
        setErrorMessage(getErrorMessage(err));
        setState('error');
      });
  }, []);

  useEffect(() => {
    fetchTasks(page, pageSize);
  }, [fetchTasks, page, pageSize]);

  const retry = useCallback(() => {
    setState('loading');
    fetchTasks(page, pageSize);
  }, [fetchTasks, page, pageSize]);

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
        error={state === 'error' ? errorMessage : null}
        onRetry={retry}
        emptyTitle="No tasks assigned"
        emptyDescription="Tasks assigned to you will show up here."
        pagination={{
          page,
          pageSize,
          totalCount: total,
          onPageChange: setPage,
          onPageSizeChange: (size) => {
            setPageSize(size);
            setPage(0);
          },
        }}
      />
    </PageContainer>
  );
}
