'use client';

import { useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import Logo from '@/components/layout/Logo';
import { PASSWORD_MIN_LENGTH, passwordProblem } from '@/services/passwordRules';

type ChangePasswordFormProps = {
  /** Shown in the greeting, e.g. "Welcome, Dana". */
  displayName?: string;
  /** Called with the new password once both fields are valid and match. */
  onSubmit: (newPassword: string) => void;
  submitting?: boolean;
  /** A server error to show under the button. */
  error?: string | null;
};

/**
 * The screen a user sees on their first sign-in: the manager gave them an
 * initial password, and they replace it with their own before going on.
 */
export default function ChangePasswordForm({
  displayName,
  onSubmit,
  submitting = false,
  error,
}: ChangePasswordFormProps) {
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');

  const newPasswordError = passwordProblem(newPassword);
  const mismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const canSubmit = newPasswordError === null && confirmPassword === newPassword && !submitting;

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    // Stop the browser's own form submit, which would reload the page
    event.preventDefault();
    if (canSubmit) onSubmit(newPassword);
  };

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        bgcolor: 'background.default',
        p: 2,
      }}
    >
      <Paper sx={{ p: 4, maxWidth: 400, width: '100%' }} elevation={0} variant="outlined">
        <Stack component="form" onSubmit={handleSubmit} spacing={2.5}>
          <Box sx={{ alignSelf: 'center' }}>
            <Logo variant="light" />
          </Box>
          <Box>
            <Typography variant="h6" component="h1">
              {displayName ? `Welcome, ${displayName}` : 'Set a new password'}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              This is your first sign-in. Choose a new password to replace the one your manager gave you.
            </Typography>
          </Box>
          <TextField
            label="New password"
            type="password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            autoComplete="new-password"
            required
            fullWidth
            // Only flagged once the user started typing, not on an empty form.
            error={!!newPassword && newPasswordError !== null}
            helperText={newPassword && newPasswordError ? newPasswordError : `At least ${PASSWORD_MIN_LENGTH} characters`}
          />
          <TextField
            label="Confirm new password"
            type="password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            autoComplete="new-password"
            required
            fullWidth
            error={mismatch}
            helperText={mismatch ? "Passwords don't match" : ' '}
          />
          <Button type="submit" variant="contained" size="large" disabled={!canSubmit}>
            {submitting ? 'Saving…' : 'Save password'}
          </Button>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </Paper>
    </Box>
  );
}
