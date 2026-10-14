'use client';

import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Link from 'next/link';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlined';
import Logo from '@/components/layout/Logo';

export default function UnauthorizedPage() {
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
      <Paper
        sx={{ p: 4, maxWidth: 380, width: '100%', textAlign: 'center' }}
        elevation={0}
        variant="outlined"
      >
        <Stack spacing={2.5} sx={{ alignItems: 'center' }}>
          <Logo variant="light" />
          <ErrorOutlineIcon sx={{ fontSize: 40, color: 'error.main' }} />
          <Typography variant="h4" component="h1">
            Access denied
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Your role does not have access to this page. This route is reserved for the
            role-mismatch redirect once authentication is implemented.
          </Typography>
          <Button component={Link} href="/" variant="contained" fullWidth>
            Back to dashboard
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}
