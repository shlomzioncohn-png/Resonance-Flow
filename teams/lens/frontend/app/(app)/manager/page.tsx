import { Suspense } from 'react';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import ManagerDashboardView from './ManagerDashboardView';

export default function ManagerDashboardPage() {
  return (
    <PageContainer title="Dashboard" description="What needs your attention across the team.">
      {/* The dashboard filters live in the URL (?period=&project=), which needs a Suspense boundary to build. */}
      <Suspense fallback={<LoadingState message="Loading dashboard…" />}>
        <ManagerDashboardView />
      </Suspense>
    </PageContainer>
  );
}
