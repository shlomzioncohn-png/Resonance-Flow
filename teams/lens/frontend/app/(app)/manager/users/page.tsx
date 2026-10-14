'use client';

import { useCallback, useEffect, useState } from 'react';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import DataTable, { type DataTableColumn } from '@/components/ui/DataTable';
import StatusBadge, { type StatusTone } from '@/components/ui/StatusBadge';
import { ApiError } from '@/services/api';
import { getUsers, type User, type UserRole, type UserStatus } from '@/services/usersApi';

type Status = 'loading' | 'ready' | 'error';

const ROLE_LABELS: Record<UserRole, string> = {
  EMPLOYEE: 'Employee',
  TEAM_LEAD: 'Team lead',
  TENANT_ADMIN: 'Tenant admin',
};

const STATUS_BADGES: Record<UserStatus, { tone: StatusTone; label: string }> = {
  ACTIVE: { tone: 'healthy', label: 'Active' },
  PENDING: { tone: 'warning', label: 'Pending' },
  SUSPENDED: { tone: 'critical', label: 'Suspended' },
  DISABLED: { tone: 'neutral', label: 'Disabled' },
};

const lastLoginFormat = new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' });

const columns: DataTableColumn<User>[] = [
  { key: 'name', header: 'Name', render: (user) => user.displayName },
  { key: 'email', header: 'Email', render: (user) => user.email },
  { key: 'role', header: 'Role', render: (user) => ROLE_LABELS[user.role] ?? user.role },
  {
    key: 'status',
    header: 'Status',
    render: (user) => {
      const badge = STATUS_BADGES[user.status] ?? { tone: 'neutral', label: user.status };
      return <StatusBadge status={badge.tone} label={badge.label} />;
    },
  },
  {
    key: 'lastLogin',
    header: 'Last login',
    render: (user) =>
      user.lastLoginAt ? lastLoginFormat.format(new Date(user.lastLoginAt)) : 'Never',
  },
];

// services/api.ts's getErrorMessage() is worded for tasks, so this screen has its own.
function getUsersErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    // fetch() throws a TypeError when the Gateway can't be reached at all
    return 'The users service is unreachable. Check your connection and try again.';
  }
  switch (error.status) {
    case 401:
      return "We couldn't verify who you are. Please sign in again.";
    case 403:
      return "You don't have permission to view users.";
    default:
      return "The users list isn't available right now. Please try again in a moment.";
  }
}

export default function ManagerUsersPage() {
  const [status, setStatus] = useState<Status>('loading');
  const [users, setUsers] = useState<User[]>([]);
  const [errorMessage, setErrorMessage] = useState('');

  const fetchUsers = useCallback(() => {
    return getUsers()
      .then((page) => {
        setUsers(page.items);
        setStatus('ready');
      })
      .catch((err) => {
        setErrorMessage(getUsersErrorMessage(err));
        setStatus('error');
      });
  }, []);

  const retry = useCallback(() => {
    setStatus('loading');
    fetchUsers();
  }, [fetchUsers]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  return (
    <PageContainer title="Users" description="Everyone in your organization and their access.">
      {status === 'loading' && <LoadingState message="Loading users…" />}

      {status === 'ready' && (
        <DataTable
          columns={columns}
          rows={users}
          getRowId={(user) => user.id}
          emptyState={
            <EmptyState
              title="No users yet"
              description="Users will appear here once they are added to your organization."
            />
          }
        />
      )}

      {status === 'error' && (
        <ErrorState title="Couldn't load users" description={errorMessage} onRetry={retry} />
      )}
    </PageContainer>
  );
}
