'use client'

import { useCallback, useEffect, useState } from 'react'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PageContainer from '@/components/layout/PageContainer'
import LoadingState from '@/components/feedback/LoadingState'
import EmptyState from '@/components/feedback/EmptyState'
import ErrorState from '@/components/feedback/ErrorState'
import StatusBadge from '@/components/ui/StatusBadge'
import { fetchManagerWorkload, fetchManagerWorkloadWithError } from '@/mocks/managerWorkload'

type Status = 'loading' | 'empty' | 'error'

export default function ManagerWorkloadPage() {
  const [status, setStatus] = useState<Status>('loading')

  const load = useCallback((simulateError = false) => {
    setStatus('loading')
    const request = simulateError ? fetchManagerWorkloadWithError() : fetchManagerWorkload()
    request.then(() => setStatus('empty')).catch(() => setStatus('error'))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return (
    <PageContainer
      title="Team workload"
      description="Per-employee and per-project breakdown of active work."
    >
      <Stack spacing={1} sx={{ mb: 4 }}>
        <Typography variant="overline" color="text.secondary">
          Workflow health legend — design system reference
        </Typography>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
          <StatusBadge status="healthy" label="Healthy / Good" />
          <StatusBadge status="warning" label="At risk / Warning" />
          <StatusBadge status="critical" label="Critical / Blocked" />
          <StatusBadge status="neutral" label="Unknown / N/A" />
        </Stack>
      </Stack>

      {status === 'loading' && <LoadingState message="Loading team workload…" />}

      {status === 'empty' && (
        <EmptyState
          title="No workload data yet"
          description="This page demonstrates the App Shell foundation end to end: it fetches through a mock adapter and renders the Loading and Empty states. Inbox and Chain integration will replace the mock later."
          action={
            <Button variant="outlined" color="error" onClick={() => load(true)}>
              Simulate error state
            </Button>
          }
        />
      )}

      {status === 'error' && (
        <ErrorState
          description="Simulated failure — demonstrates the ErrorState. Click below to recover."
          onRetry={() => load(false)}
        />
      )}
    </PageContainer>
  )
}
