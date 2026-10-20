import { Suspense } from 'react';
import PageContainer from '@/components/layout/PageContainer';
import LoadingState from '@/components/feedback/LoadingState';
import BottlenecksView from './BottlenecksView';

export default function ManagerBottlenecksPage() {
  return (
    <PageContainer
      title="Bottlenecks"
      description="Which tasks are holding up the most work, from Chain's dependency graph."
    >
      {/* BottlenecksView reads ?projectId= from the URL, which needs a Suspense boundary to build. */}
      <Suspense fallback={<LoadingState message="Loading dependency graph…" />}>
        <BottlenecksView />
      </Suspense>
    </PageContainer>
  );
}
