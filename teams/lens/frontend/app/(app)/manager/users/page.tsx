'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import PageContainer from '@/components/layout/PageContainer';
import DataTable, { type DataTableSort } from '@/components/ui/DataTable';
import {
  ApiError,
  getUser,
  listUsers,
  updateUser,
  updateUserStatus,
  type UserDetails,
  type UserEditFields,
  type UserRow,
  type UserStatus,
} from '@/services/managerUsersApi';
import { buildUserColumns } from './userColumns';

type LoadState = 'loading' | 'loaded' | 'error';

const STATUS_ACTIONS: Record<UserStatus, string> = {
  ACTIVE: 'Reactivate',
  SUSPENDED: 'Suspend',
  DISABLED: 'Disable',
};

function errorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return "You don't have permission for this screen.";
    if (error.status === 503) return 'The service is unavailable right now. Please try again.';
    if (error.message) return error.message;
  }
  return "Couldn't load users. Please try again.";
}

export default function ManagerUsersPage() {
  const [state, setState] = useState<LoadState>('loading');
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [users, setUsers] = useState<UserRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [sort, setSort] = useState<DataTableSort>({ columnId: 'displayName', direction: 'asc' });

  const [editingUser, setEditingUser] = useState<UserDetails | null>(null);
  const [editForm, setEditForm] = useState<UserEditFields>({});
  const [editError, setEditError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; row: UserRow } | null>(null);
  const [statusTarget, setStatusTarget] = useState<{ row: UserRow; newStatus: UserStatus } | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [statusSubmitting, setStatusSubmitting] = useState(false);

  // `isCurrent` lets the effect below drop a response that arrives after page/size/sort moved on.
  const fetchUsers = useCallback((isCurrent: () => boolean = () => true) => {
    return listUsers({ page, size, sort: `${sort.columnId},${sort.direction}` })
      .then((response) => {
        if (!isCurrent()) return;
        setUsers(response.items);
        setTotal(response.totalElements);
        setState('loaded');
      })
      .catch((error) => {
        if (!isCurrent()) return;
        setErrorMessage(errorMessageFor(error));
        setState('error');
      });
  }, [page, size, sort]);

  // Mount + page/size/sort changes: effect body never calls setState synchronously —
  // the loading transition happens in goToPage/changePageSize/changeSort instead.
  useEffect(() => {
    let current = true;
    fetchUsers(() => current);
    return () => {
      current = false;
    };
  }, [fetchUsers]);

  const retry = useCallback(() => {
    setState('loading');
    fetchUsers();
  }, [fetchUsers]);

  const goToPage = useCallback((newPage: number) => {
    setState('loading');
    setPage(newPage);
  }, []);

  const changePageSize = useCallback((newSize: number) => {
    setState('loading');
    setSize(newSize);
    setPage(0);
  }, []);

  const changeSort = useCallback((newSort: DataTableSort) => {
    setState('loading');
    setSort(newSort);
    setPage(0);
  }, []);

  const openEdit = useCallback((row: UserRow) => {
    setEditError(null);
    getUser(row.id)
      .then((details) => {
        setEditingUser(details);
        setEditForm({
          displayName: details.displayName,
          firstName: details.firstName ?? '',
          lastName: details.lastName ?? '',
          phone: details.phone ?? '',
        });
      })
      .catch((error) => {
        if (error instanceof ApiError && error.status === 404) {
          setErrorMessage('User not found.');
          fetchUsers();
        } else {
          setEditError(errorMessageFor(error));
        }
      });
  }, [fetchUsers]);

  const closeEdit = useCallback(() => {
    setEditingUser(null);
    setEditError(null);
  }, []);

  const changedFields = useMemo<UserEditFields>(() => {
    if (!editingUser) return {};
    const changed: UserEditFields = {};
    if (editForm.displayName !== editingUser.displayName) changed.displayName = editForm.displayName;
    if ((editForm.firstName ?? '') !== (editingUser.firstName ?? '')) changed.firstName = editForm.firstName;
    if ((editForm.lastName ?? '') !== (editingUser.lastName ?? '')) changed.lastName = editForm.lastName;
    if ((editForm.phone ?? '') !== (editingUser.phone ?? '')) changed.phone = editForm.phone;
    return changed;
  }, [editForm, editingUser]);

  const hasChanges = Object.keys(changedFields).length > 0;

  const saveEdit = useCallback(() => {
    if (!editingUser || !hasChanges) return;
    setSaving(true);
    setEditError(null);
    updateUser(editingUser.id, changedFields)
      .then((updated) => {
        setUsers((rows) => rows.map((r) => (r.id === updated.id ? { ...r, ...updated } : r)));
        setSaving(false);
        setEditingUser(null);
      })
      .catch((error) => {
        setSaving(false);
        setEditError(errorMessageFor(error));
      });
  }, [editingUser, hasChanges, changedFields]);

  const openStatusMenu = (event: React.MouseEvent<HTMLElement>, row: UserRow) => {
    setMenuAnchor({ el: event.currentTarget, row });
  };
  const closeStatusMenu = () => setMenuAnchor(null);

  const requestStatusChange = (row: UserRow, newStatus: UserStatus) => {
    setStatusError(null);
    setStatusTarget({ row, newStatus });
    closeStatusMenu();
  };

  const confirmStatusChange = useCallback(() => {
    if (!statusTarget) return;
    setStatusSubmitting(true);
    setStatusError(null);
    updateUserStatus(statusTarget.row.id, statusTarget.newStatus)
      .then((updated) => {
        setUsers((rows) => rows.map((r) => (r.id === updated.id ? { ...r, status: updated.status } : r)));
        setStatusSubmitting(false);
        setStatusTarget(null);
      })
      .catch((error) => {
        setStatusSubmitting(false);
        setStatusError(errorMessageFor(error));
      });
  }, [statusTarget]);

  const columns = buildUserColumns({
    onEdit: openEdit,
    onChangeStatus: openStatusMenu,
  });

  return (
    <PageContainer title="Users" description="Every user in your organization, from Inbox's canonical user table.">
      <DataTable
        ariaLabel="Users"
        columns={columns}
        rows={users}
        getRowId={(user) => user.id}
        loading={state === 'loading'}
        error={state === 'error' ? errorMessage : null}
        onRetry={retry}
        emptyTitle="No users found"
        emptyDescription="Users in your organization will show up here."
        sort={sort}
        onSortChange={changeSort}
        pagination={{
          page,
          pageSize: size,
          totalCount: total,
          onPageChange: goToPage,
          onPageSizeChange: changePageSize,
        }}
      />

      {/* Status menu */}
      <Menu anchorEl={menuAnchor?.el} open={!!menuAnchor} onClose={closeStatusMenu}>
        {(['ACTIVE', 'SUSPENDED', 'DISABLED'] as UserStatus[])
          .filter((status) => status !== menuAnchor?.row.status)
          .map((status) => (
            <MenuItem key={status} onClick={() => menuAnchor && requestStatusChange(menuAnchor.row, status)}>
              {STATUS_ACTIONS[status]}
            </MenuItem>
          ))}
      </Menu>

      {/* Status change confirmation */}
      <Dialog open={!!statusTarget} onClose={() => !statusSubmitting && setStatusTarget(null)}>
        <DialogTitle>
          {statusTarget && `${STATUS_ACTIONS[statusTarget.newStatus]} ${statusTarget.row.displayName}?`}
        </DialogTitle>
        <DialogContent>
          <DialogContentText>
            {statusTarget?.newStatus === 'SUSPENDED' &&
              'The user will no longer be able to sign in once their current session expires — this will take effect within an hour at most.'}
            {statusTarget?.newStatus === 'DISABLED' &&
              'The user will be disabled and unable to sign in once their current session expires — this will take effect within an hour at most.'}
            {statusTarget?.newStatus === 'ACTIVE' && 'The user will be reactivated and able to sign in again.'}
          </DialogContentText>
          {statusError && (
            <Typography color="error" variant="body2" sx={{ mt: 2 }}>
              {statusError}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setStatusTarget(null)} disabled={statusSubmitting}>
            Cancel
          </Button>
          <Button onClick={confirmStatusChange} disabled={statusSubmitting} variant="contained">
            Confirm
          </Button>
        </DialogActions>
      </Dialog>

      {/* Edit dialog */}
      <Dialog open={!!editingUser} onClose={closeEdit} fullWidth maxWidth="sm">
        <DialogTitle>Edit user</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Display name"
              value={editForm.displayName ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, displayName: e.target.value }))}
              fullWidth
              required
            />
            <Stack direction="row" spacing={2}>
              <TextField
                label="First name"
                value={editForm.firstName ?? ''}
                onChange={(e) => setEditForm((f) => ({ ...f, firstName: e.target.value }))}
                fullWidth
              />
              <TextField
                label="Last name"
                value={editForm.lastName ?? ''}
                onChange={(e) => setEditForm((f) => ({ ...f, lastName: e.target.value }))}
                fullWidth
              />
            </Stack>
            <TextField
              label="Phone"
              value={editForm.phone ?? ''}
              onChange={(e) => setEditForm((f) => ({ ...f, phone: e.target.value }))}
              fullWidth
            />
            <Stack direction="row" spacing={2}>
              <TextField label="Email" value={editingUser?.email ?? ''} fullWidth disabled />
              <TextField label="Username" value={editingUser?.username ?? ''} fullWidth disabled />
            </Stack>
            <TextField label="Role" value={editingUser?.role ?? ''} fullWidth disabled />
            {editError && <Typography color="error" variant="body2">{editError}</Typography>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeEdit} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={saveEdit} disabled={!hasChanges || saving} variant="contained">
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </PageContainer>
  );
}
