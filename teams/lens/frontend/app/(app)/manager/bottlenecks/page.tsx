import PageContainer from '@/components/layout/PageContainer';
import EmptyState from '@/components/feedback/EmptyState';

export default function ManagerBottlenecksPage() {
  return (
    <PageContainer
      title="Bottlenecks"
      description="Aggregate dependency and process-problem indicators from Chain."
    >
      <EmptyState
        title="No bottlenecks detected yet"
        description="This view will visualize dependency and process bottlenecks once connected to Chain data."
      />
    </PageContainer>
  );
}
