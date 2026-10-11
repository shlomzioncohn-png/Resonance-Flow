'use client'

import { useState } from 'react'
import Button from '@mui/material/Button'
import PageContainer from '@/components/layout/PageContainer'
import LoadingState from '@/components/feedback/LoadingState'
import EmptyState from '@/components/feedback/EmptyState'
import ErrorState from '@/components/feedback/ErrorState'
import { useManagerWorkload } from '@/hooks/useManagerWorkload'

export default function ManagerWorkloadPage() {
  const [simulateError, setSimulateError] = useState(false)
  const { data, isPending, isError } = useManagerWorkload({ simulateError })

  return (
    <PageContainer
      title="Team workload"
      description="Per-employee and per-project breakdown of active work."
    >
      {isPending && <LoadingState message="Loading team workload…" />}

      {data?.length === 0 && (
        <EmptyState
          title="No workload data yet"
          description="This page demonstrates the App Shell foundation end to end: it fetches through a mock adapter and renders the Loading and Empty states. Inbox and Chain integration will replace the mock later."
          action={
            <Button variant="outlined" color="error" onClick={() => setSimulateError(true)}>
              Simulate error state
            </Button>
          }
        />
      )}

      {isError && (
        <ErrorState
          description="Simulated failure — demonstrates the ErrorState. Click below to recover."
          onRetry={() => setSimulateError(false)}
        />
      )}
    </PageContainer>
  )
}
