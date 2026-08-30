'use client'

import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Paper from '@mui/material/Paper'
import Link from 'next/link'
import ErrorState from '@/components/feedback/ErrorState'

export default function NotFound() {
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
      <Paper sx={{ p: 4, maxWidth: 420, width: '100%' }} elevation={0} variant="outlined">
        <ErrorState
          title="Page not found"
          description="There is no route at this address."
        />
        <Box sx={{ textAlign: 'center' }}>
          <Button component={Link} href="/" variant="contained">
            Back to dashboard
          </Button>
        </Box>
      </Paper>
    </Box>
  )
}
