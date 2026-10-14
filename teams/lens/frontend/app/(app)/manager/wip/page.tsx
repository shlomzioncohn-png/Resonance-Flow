import PageContainer from '@/components/layout/PageContainer';
import EmptyState from '@/components/feedback/EmptyState';

export default function ManagerWipPage() {
  return (
    <PageContainer
      title="Work in progress"
      description="Tasks currently being worked on across the team."
    >
      <EmptyState
        title="No work-in-progress data yet"
        description="This view will list active tasks across the team once connected to Inbox and Chain data."
      />
    </PageContainer>
  );
}
