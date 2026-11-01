import { Suspense } from 'react';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import FlowHealthView from './FlowHealthView';

export default function ManagerFlowHealthPage() {
  return (
    <PageContainer title="Flow health" description="דפוסים לאורך זמן, המלצה בלבד">
      {/* FlowHealthView reads its filters from the URL, which needs a Suspense boundary to build. */}
      <Suspense fallback={<LoadingState message="Loading flow health…" />}>
        <FlowHealthView />
      </Suspense>
    </PageContainer>
  );
}
