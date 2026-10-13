'use client';

import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import { getErrorMessage } from '@/services/api';
import { useManagerWorkload } from '@/hooks/useManagerWorkload';

export default function ManagerWorkloadPage() {
  const { data, isPending, isError, error, refetch } = useManagerWorkload();

  return (
    <PageContainer
      title="Team workload"
      description="Per-employee and per-project breakdown of active work."
    >
      {isPending && <LoadingState message="Loading team workload…" />}

      {data?.length === 0 && (
        <EmptyState
          title="No workload data yet"
          description="Workload will appear here once Inbox and Chain provide it."
        />
      )}

      {isError && <ErrorState description={getErrorMessage(error)} onRetry={() => refetch()} />}
    </PageContainer>
  );
}
