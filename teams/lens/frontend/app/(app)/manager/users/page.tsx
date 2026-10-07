'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TablePagination from '@mui/material/TablePagination';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import DataTable, { type DataTableColumn } from '@/components/ui/DataTable';
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

/**
 * TODO: this screen doesn't know who's logged in — Auth stage 3 (real
 * Keycloak session in the Frontend) hasn't landed, so there is no real
 * "current manager" to read. Gateway's ManagerUsersController forwards
 * every request under the same mock actor id (app.mock-actor-id,
 * default below) regardless of who's actually signed in — see that
 * class's javadoc. This constant exists only so the one row that *is*
 * that mock actor can have its status-change action disabled, matching
 * the backend's own "can't change your own status" rule. Replace with
 * the real signed-in user's id once a session exists.
 */
const MOCK_CURRENT_USER_ID = '00000000-0000-0000-0000-000000000001';

type LoadState = 'loading' | 'loaded' | 'error';

const STATUS_COLOR: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
  ACTIVE: 'success',
  PENDING: 'warning',
  SUSPENDED: 'warning',
  DISABLED: 'error',
};

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

  const [editingUser, setEditingUser] = useState<UserDetails | null>(null);
  const [editForm, setEditForm] = useState<UserEditFields>({});
  const [editError, setEditError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [menuAnchor, setMenuAnchor] = useState<{ el: HTMLElement; row: UserRow } | null>(null);
  const [statusTarget, setStatusTarget] = useState<{ row: UserRow; newStatus: UserStatus } | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [statusSubmitting, setStatusSubmitting] = useState(false);

  const fetchUsers = useCallback(() => {
    return listUsers({ page, size, sort: 'displayName,asc' })
      .then((response) => {
        setUsers(response.items);
        setTotal(response.totalElements);
        setState('loaded');
      })
      .catch((error) => {
        setErrorMessage(errorMessageFor(error));
        setState('error');
      });
  }, [page, size]);

  // Mount + page/size changes: effect body never calls setState synchronously —
  // the loading transition for page/size changes happens in goToPage/changePageSize instead.
  useEffect(() => {
    fetchUsers();
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

  const columns: DataTableColumn<UserRow>[] = [
    {
      key: 'name',
      header: 'Name',
      render: (user) => (
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {user.displayName}
        </Typography>
      ),
    },
    { key: 'email', header: 'Email', render: (user) => user.email },
    { key: 'role', header: 'Role', render: (user) => user.role },
    {
      key: 'status',
      header: 'Status',
      render: (user) => (
        <Chip size="small" label={user.status} color={STATUS_COLOR[user.status] ?? 'default'} />
      ),
    },
    {
      key: 'lastLogin',
      header: 'Last login',
      render: (user) => (user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : '—'),
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (user) => {
        const isSelf = user.id === MOCK_CURRENT_USER_ID;
        return (
          <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
            <Tooltip title="Edit">
              <IconButton size="small" onClick={() => openEdit(user)} aria-label={`Edit ${user.displayName}`}>
                <EditOutlinedIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={isSelf ? "You can't change your own status" : 'Change status'}>
              <span>
                <IconButton
                  size="small"
                  disabled={isSelf}
                  onClick={(event) => openStatusMenu(event, user)}
                  aria-label={`Change status for ${user.displayName}`}
                >
                  <MoreVertIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        );
      },
    },
  ];

  return (
    <PageContainer title="Users" description="Every user in your organization, from Inbox's canonical user table.">
      {state === 'loading' && <LoadingState message="Loading users…" />}

      {state === 'error' && <ErrorState description={errorMessage} onRetry={retry} />}

      {state === 'loaded' && (
        <>
          <DataTable
            columns={columns}
            rows={users}
            getRowId={(user) => user.id}
            emptyState={<EmptyState title="No users found" description="Users in your organization will show up here." />}
          />
          {users.length > 0 && (
            <TablePagination
              component="div"
              count={total}
              page={page}
              onPageChange={(_, newPage) => goToPage(newPage)}
              rowsPerPage={size}
              onRowsPerPageChange={(event) => changePageSize(parseInt(event.target.value, 10))}
              rowsPerPageOptions={[10, 20, 50, 100]}
            />
          )}
        </>
      )}

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
