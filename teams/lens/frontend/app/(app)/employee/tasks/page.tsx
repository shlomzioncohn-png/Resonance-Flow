'use client';

import { useCallback, useEffect, useState } from 'react';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import DataTable, { type DataTableColumn } from '@/components/ui/DataTable';
import { getErrorMessage } from '@/services/api';
import { listTasks, type ApiTask } from '@/services/workspaceApi';
import { PriorityChip, softToneSx, statusTone } from '../shared';

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
        {task.project && (
          <Typography variant="caption" color="text.secondary">
            {task.project}
          </Typography>
        )}
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
    render: (task) => <PriorityChip priority={task.priority} />,
  },
  {
    key: 'owner',
    header: 'Owner',
    render: (task) => task.owner ?? '—',
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
      {state === 'loading' && <LoadingState message="Loading your tasks…" />}

      {state === 'error' && <ErrorState description={errorMessage} onRetry={retry} />}

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

      {state === 'loaded' && total > 0 && (
        <TablePagination
          component="div"
          count={total}
          page={page}
          onPageChange={(_, newPage) => setPage(newPage)}
          rowsPerPage={pageSize}
          rowsPerPageOptions={[20, 50, 100]}
          onRowsPerPageChange={(event) => {
            setPageSize(Number(event.target.value));
            setPage(0);
          }}
        />
      )}
    </PageContainer>
  );
}
