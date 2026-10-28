import PageContainer from '@/components/layout/PageContainer';
import ManagerTaskTable from '../ManagerTaskTable';

export default function ManagerWipPage() {
  return (
    <PageContainer
      title="Work in progress"
      description="Tasks currently being worked on across the team, soonest deadline first."
    >
      <ManagerTaskTable
        view="in-progress"
        ariaLabel="Tasks in progress"
        emptyTitle="Nothing in progress"
        emptyDescription="Tasks the team starts working on will show up here."
      />
    </PageContainer>
  );
}
