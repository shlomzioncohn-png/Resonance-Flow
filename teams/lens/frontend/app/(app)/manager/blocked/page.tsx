import PageContainer from '@/components/layout/PageContainer';
import ManagerTaskTable from '../ManagerTaskTable';

export default function ManagerBlockedPage() {
  return (
    <PageContainer
      title="Blocked & delayed work"
      description="Open tasks that are blocked by other work or past their deadline, soonest deadline first."
    >
      <ManagerTaskTable
        view="blocked-delayed"
        ariaLabel="Blocked and delayed tasks"
        emptyTitle="Nothing blocked or delayed"
        emptyDescription="Every open task is unblocked and on time."
      />
    </PageContainer>
  );
}
