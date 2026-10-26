'use client';

import { useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Logo from '@/components/layout/Logo';
import { useRole } from '@/components/layout/RoleProvider';
import { homeFor } from '@/components/layout/Sidebar/navConfig';
import { getErrorMessage } from '@/services/api';
import { login } from '@/services/authApi';

export default function LoginPage() {
  const { signIn } = useRole();
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    // Stop the browser's own form submit, which would reload the page
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const response = await login({ login: email, password });
      // The token itself is in the Gateway's HttpOnly cookie - the browser sends it on its own
      signIn(response.user);
      router.push(homeFor(response.user.role));
    } catch (err) {
      setError(getErrorMessage(err, '/auth/login'));
      setSubmitting(false);
    }
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
          <TextField
            // Plain text, not type="email": the accounts sign in with a username
            // (manager1) as well as an email, and the browser would block a username.
            label="Email or username"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="username"
            required
            fullWidth
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
            fullWidth
          />
          <Button type="submit" variant="contained" size="large" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </Paper>
    </Box>
  );
}
