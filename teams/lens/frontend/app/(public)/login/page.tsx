'use client'

import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import Link from 'next/link'
import Logo from '@/components/layout/Logo'

export default function LoginPage() {
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
      <Paper sx={{ p: 4, maxWidth: 380, width: '100%', textAlign: 'center' }} elevation={0} variant="outlined">
        <Stack spacing={2.5} sx={{ alignItems: 'center' }}>
          <Logo variant="light" />
          <Typography variant="body2" color="text.secondary">
            Authentication is not implemented yet in this foundation phase. This route is
            reserved as the public entry point for the sign-in flow.
          </Typography>
          <Button component={Link} href="/" variant="contained" fullWidth>
            Continue to dashboard
          </Button>
        </Stack>
      </Paper>
    </Box>
  )
}
