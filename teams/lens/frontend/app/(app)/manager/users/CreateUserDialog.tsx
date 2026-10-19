'use client';

import { useState } from 'react';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import {
  ApiError,
  createUser,
  type UserCreateFields,
  type UserDetails,
} from '@/services/managerUsersApi';

// Same loose check the Gateway/Inbox apply (@Email) — just enough to catch typos before Save.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const EMPTY_FORM: UserCreateFields = {
  displayName: '',
  firstName: '',
  lastName: '',
  email: '',
  phone: '',
  username: '',
  role: 'EMPLOYEE',
};

function createErrorMessageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return error.message || 'A user with this email or username already exists.';
    if (error.status === 403) return "You don't have permission to create users.";
    if (error.status === 503) return 'The service is unavailable right now. Please try again.';
    if (error.message) return error.message;
  }
  return "Couldn't create the user. Please try again.";
}

/** Blank optional fields are left out of the request rather than sent as "". */
function toRequest(form: UserCreateFields): UserCreateFields {
  const request: UserCreateFields = { displayName: form.displayName.trim(), role: form.role };
  for (const key of ['firstName', 'lastName', 'email', 'phone', 'username'] as const) {
    const value = form[key]?.trim();
    if (value) request[key] = value;
  }
  return request;
}

export interface CreateUserDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (user: UserDetails) => void;
}

/**
 * "New user" form. The user is created in the acting manager's own tenant
 * (the BFF looks it up) — so there is deliberately no tenant field here.
 */
export default function CreateUserDialog({ open, onClose, onCreated }: CreateUserDialogProps) {
  const [form, setForm] = useState<UserCreateFields>(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const emailInvalid = !!form.email && !EMAIL_PATTERN.test(form.email.trim());
  const canSave = form.displayName.trim().length > 0 && !emailInvalid && !saving;

  const close = () => {
    if (saving) return;
    setForm(EMPTY_FORM);
    setError(null);
    onClose();
  };

  const save = () => {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    createUser(toRequest(form))
      .then((created) => {
        setSaving(false);
        setForm(EMPTY_FORM);
        onCreated(created);
      })
      .catch((err) => {
        setSaving(false);
        setError(createErrorMessageFor(err));
      });
  };

  const field = (key: keyof UserCreateFields) => ({
    value: form[key] ?? '',
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => ({ ...f, [key]: e.target.value })),
    fullWidth: true,
  });

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="sm" aria-labelledby="create-user-title">
      <DialogTitle id="create-user-title">New user</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Display name" required {...field('displayName')} />
          <Stack direction="row" spacing={2}>
            <TextField label="First name" {...field('firstName')} />
            <TextField label="Last name" {...field('lastName')} />
          </Stack>
          <TextField
            label="Email"
            type="email"
            {...field('email')}
            error={emailInvalid}
            helperText={emailInvalid ? 'Enter a valid email address' : undefined}
          />
          <Stack direction="row" spacing={2}>
            <TextField label="Username" {...field('username')} />
            <TextField label="Phone" {...field('phone')} />
          </Stack>
          <TextField select label="Role" required {...field('role')}>
            <MenuItem value="EMPLOYEE">Employee</MenuItem>
            <MenuItem value="MANAGER">Manager</MenuItem>
          </TextField>
          {error && (
            <Typography color="error" variant="body2" role="alert">
              {error}
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={close} disabled={saving}>
          Cancel
        </Button>
        <Button onClick={save} disabled={!canSave} variant="contained">
          Create
        </Button>
      </DialogActions>
    </Dialog>
  );
}
