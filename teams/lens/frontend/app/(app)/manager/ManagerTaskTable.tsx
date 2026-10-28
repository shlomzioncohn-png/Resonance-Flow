'use client';

import { useState } from 'react';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import DataTable, { type Column } from '@/components/ui/DataTable';
import { useManagerTasks } from '@/hooks/useManagerTasks';
import { getErrorMessage } from '@/services/api';
import type { ManagerTaskView } from '@/services/managerApi';
import type { ApiTask } from '@/services/workspaceApi';
import { PriorityChip, softToneSx, statusTone } from '../employee/shared';

/** Inbox's deadline ("21/10/2026", Israel time) is before today. */
export function isPastDue(dueDate: string | undefined, today = new Date()): boolean {
  const match = dueDate?.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
  if (!match) return false;
  const [, day, month, year] = match;
  const due = new Date(Number(year), Number(month) - 1, Number(day));
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  return due < startOfToday;
}

const baseColumns: Column<ApiTask>[] = [
  {
    id: 'title',
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
    id: 'status',
    header: 'Status',
    render: (task) => <Chip size="small" label={task.status} sx={softToneSx(statusTone(task.status))} />,
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
    render: (task) => task.dueDate ?? '—',
  },
];

/** Why a task is on the Blocked & delayed list - one or both. */
const reasonColumn: Column<ApiTask> = {
  id: 'reason',
  header: 'Why',
  render: (task) => (
    <Stack direction="row" spacing={0.5}>
      {task.isAvailable === false && <Chip size="small" label="Blocked" sx={softToneSx('error')} />}
      {isPastDue(task.dueDate) && <Chip size="small" label="Overdue" sx={softToneSx('warning')} />}
    </Stack>
  ),
};

type ManagerTaskTableProps = {
  view: ManagerTaskView;
  ariaLabel: string;
  emptyTitle: string;
  emptyDescription: string;
};

/**
 * The tenant's tasks for one manager screen, real data from the BFF. The
 * whole list comes in one response; paging happens here.
 */
export default function ManagerTaskTable({
  view,
  ariaLabel,
  emptyTitle,
  emptyDescription,
}: ManagerTaskTableProps) {
  const tasks = useManagerTasks(view);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  const rows = tasks.data ?? [];
  const columns = view === 'blocked-delayed' ? [...baseColumns, reasonColumn] : baseColumns;

  return (
    <DataTable
      ariaLabel={ariaLabel}
      columns={columns}
      rows={rows.slice(page * pageSize, (page + 1) * pageSize)}
      getRowId={(task) => task.id}
      loading={tasks.isPending}
      error={tasks.isError ? getErrorMessage(tasks.error) : null}
      onRetry={() => tasks.refetch()}
      emptyTitle={emptyTitle}
      emptyDescription={emptyDescription}
      pagination={{
        page,
        pageSize,
        totalCount: rows.length,
        onPageChange: setPage,
        onPageSizeChange: (size) => {
          setPageSize(size);
          setPage(0);
        },
      }}
    />
  );
}
