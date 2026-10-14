import PageContainer from '@/components/layout/PageContainer';
import EmptyState from '@/components/feedback/EmptyState';

export default function ManagerBlockedPage() {
  return (
    <PageContainer
      title="Blocked & delayed work"
      description="Work items blocked or at risk, driven by Chain's blocker data."
    >
      <EmptyState
        title="No blocked work reported"
        description="This view will surface blocked and delayed tasks once connected to Chain's blocker data."
      />
    </PageContainer>
  );
}
