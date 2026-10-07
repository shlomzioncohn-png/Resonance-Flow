import type { MouseEvent } from 'react';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import type { Column } from '@/components/ui/DataTable';
import type { UserRow } from '@/services/managerUsersApi';

const STATUS_COLOR: Record<string, 'success' | 'warning' | 'error' | 'default'> = {
  ACTIVE: 'success',
  PENDING: 'warning',
  SUSPENDED: 'warning',
  DISABLED: 'error',
};

export interface UserColumnsOptions {
  /** The signed-in manager — their own row can't have its status changed. */
  currentUserId: string;
  onEdit: (user: UserRow) => void;
  onChangeStatus: (event: MouseEvent<HTMLElement>, user: UserRow) => void;
}

/**
 * Column definitions for the /manager/users table. Everything user-specific
 * about that table lives here; the table itself is the generic DataTable.
 * Sortable column ids double as the API's `sort` field name.
 */
export function buildUserColumns({
  currentUserId,
  onEdit,
  onChangeStatus,
}: UserColumnsOptions): Column<UserRow>[] {
  return [
    {
      id: 'displayName',
      header: 'Name',
      sortable: true,
      render: (user) => (
        <Typography variant="body2" sx={{ fontWeight: 600 }}>
          {user.displayName}
        </Typography>
      ),
    },
    { id: 'email', header: 'Email', render: (user) => user.email },
    { id: 'role', header: 'Role', render: (user) => user.role },
    {
      id: 'status',
      header: 'Status',
      render: (user) => (
        <Chip size="small" label={user.status} color={STATUS_COLOR[user.status] ?? 'default'} />
      ),
    },
    {
      id: 'lastLogin',
      header: 'Last login',
      render: (user) => (user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : '—'),
    },
    {
      id: 'actions',
      header: 'Actions',
      align: 'right',
      render: (user) => {
        const isSelf = user.id === currentUserId;
        return (
          <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
            <Tooltip title="Edit">
              <IconButton
                size="small"
                onClick={() => onEdit(user)}
                aria-label={`Edit ${user.displayName}`}
              >
                <EditOutlinedIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title={isSelf ? "You can't change your own status" : 'Change status'}>
              <span>
                <IconButton
                  size="small"
                  disabled={isSelf}
                  onClick={(event) => onChangeStatus(event, user)}
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
}
