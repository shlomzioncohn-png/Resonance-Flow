'use client';

import { useCallback, useEffect, useState } from 'react';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import { getErrorMessage } from '@/services/api';
import { getManagerWorkload } from '@/services/managerApi';

type Status = 'loading' | 'empty' | 'error';

export default function ManagerWorkloadPage() {
  const [status, setStatus] = useState<Status>('loading');
  const [errorMessage, setErrorMessage] = useState('');

  const fetchWorkload = useCallback(() => {
    return getManagerWorkload()
      .then(() => setStatus('empty'))
      .catch((err) => {
        setErrorMessage(getErrorMessage(err));
        setStatus('error');
      });
  }, []);

  const retry = useCallback(() => {
    setStatus('loading');
    fetchWorkload();
  }, [fetchWorkload]);

  useEffect(() => {
    fetchWorkload();
  }, [fetchWorkload]);

  return (
    <PageContainer
      title="Team workload"
      description="Per-employee and per-project breakdown of active work."
    >
      {status === 'loading' && <LoadingState message="Loading team workload…" />}

      {status === 'empty' && (
        <EmptyState
          title="No workload data yet"
          description="Workload will appear here once Inbox and Chain provide it."
        />
      )}

      {status === 'error' && <ErrorState description={errorMessage} onRetry={retry} />}
    </PageContainer>
  );
}
