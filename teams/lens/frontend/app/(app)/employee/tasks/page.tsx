'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import Chip from '@mui/material/Chip';
import InputAdornment from '@mui/material/InputAdornment';
import MuiLink from '@mui/material/Link';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import SearchIcon from '@mui/icons-material/Search';
import PageContainer from '@/components/layout/PageContainer';
import DataTable, { type Column } from '@/components/ui/DataTable';
import WorkInsights from '@/components/ui/WorkInsights';
import { getErrorMessage } from '@/services/api';
import { listTasks, type ApiTask, type TaskListFilter } from '@/services/workspaceApi';
import {
  displayValue,
  OpenConstraintsChip,
  PriorityChip,
  softToneSx,
  statusTone,
  urgencyLabel,
} from '../shared';

type LoadState = 'loading' | 'loaded' | 'error';

const columns: Column<ApiTask>[] = [
  {
    id: 'title',
    header: 'Task',
    render: (task) => (
      <Stack spacing={0.25}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
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
          <OpenConstraintsChip count={task.openConstraintCount} />
        </Stack>
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
    id: 'tier',
    header: 'Tier',
    render: (task) => displayValue(task.tier),
  },
  {
    id: 'urgency',
    header: 'Urgency',
    render: (task) => urgencyLabel(task.urgency),
  },
  {
    id: 'owner',
    header: 'Owner',
    render: (task) => task.owner ?? '—',
  },
  {
    id: 'due',
    header: 'Due',
    // Already formatted by Inbox (e.g. "12/10/2026") - shown as-is
    render: (task) => task.dueDate ?? '—',
  },
  {
    id: 'effort',
    header: 'Effort',
    align: 'right',
    render: (task) => (task.effortEstimate != null ? `${task.effortEstimate} min` : '—'),
  },
];

const STATUS_FILTERS: { value: TaskListFilter; label: string }[] = [
  { value: 'all', label: 'All statuses' },
  { value: 'new', label: 'New' },
  { value: 'in_review', label: 'In review' },
  { value: 'approved', label: 'Approved' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'snoozed', label: 'Snoozed' },
  { value: 'done', label: 'Done' },
  { value: 'rejected', label: 'Rejected' },
  { value: 'cancelled', label: 'Cancelled' },
];

/** How long typing has to pause before the search is sent. */
const SEARCH_DELAY_MS = 300;

export default function EmployeeTasksPage() {
  const [state, setState] = useState<LoadState>('loading');
  const [tasks, setTasks] = useState<ApiTask[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState<TaskListFilter>('all');
  // searchInput follows every keystroke; search is what's actually sent
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  // Bumped by Retry, to run the request again with the same parameters
  const [reloadKey, setReloadKey] = useState(0);
  const [errorMessage, setErrorMessage] = useState('');

  // Wait for typing to pause, so every keystroke isn't its own request
  useEffect(() => {
    const timer = setTimeout(() => setSearch(searchInput.trim()), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Filtering, search and paging all happen on the server (Inbox) - the
  // browser only ever gets the one page it shows.
  useEffect(() => {
    let ignore = false; // an older request that answers late must not overwrite a newer one
    listTasks({ filter: statusFilter, search: search || undefined, page, size: pageSize })
      .then((response) => {
        if (ignore) return;
        setTasks(response.items);
        setTotal(response.total);
        setState('loaded');
      })
      .catch((err) => {
        if (ignore) return;
        setErrorMessage(getErrorMessage(err));
        setState('error');
      });
    return () => {
      ignore = true;
    };
  }, [statusFilter, search, page, pageSize, reloadKey]);

  const retry = useCallback(() => {
    setState('loading');
    setReloadKey((key) => key + 1);
  }, []);

  const isFiltered = statusFilter !== 'all' || search !== '';

  return (
    <PageContainer
      title="My Tasks"
      description="Your complete task list — status, due dates, and everything you own."
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}>
        <TextField
          size="small"
          placeholder="Search tasks…"
          value={searchInput}
          onChange={(event) => {
            setSearchInput(event.target.value);
            setPage(0); // a new search starts from the first page
          }}
          sx={{ flexGrow: 1, maxWidth: { sm: 360 } }}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
          data-testid="task-search"
        />
        <TextField
          select
          size="small"
          label="Status"
          value={statusFilter}
          onChange={(event) => {
            setStatusFilter(event.target.value as TaskListFilter);
            setPage(0);
          }}
          sx={{ minWidth: 180 }}
          data-testid="status-filter"
        >
          {STATUS_FILTERS.map((option) => (
            <MenuItem key={option.value} value={option.value}>
              {option.label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <DataTable
        ariaLabel="My tasks"
        columns={columns}
        rows={tasks}
        getRowId={(task) => task.id}
        loading={state === 'loading'}
        error={state === 'error' ? errorMessage : null}
        onRetry={retry}
        emptyTitle={isFiltered ? 'No matching tasks' : 'No tasks assigned'}
        emptyDescription={
          isFiltered
            ? 'Try a different search or status.'
            : 'Tasks assigned to you will show up here.'
        }
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

      <WorkInsights />
    </PageContainer>
  );
}
