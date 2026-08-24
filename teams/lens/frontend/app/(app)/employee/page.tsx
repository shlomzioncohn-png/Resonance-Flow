import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default function EmployeeOverviewPage() {
  return (
    <PageContainer
      title="Employee overview"
      description="Your own workload snapshot and blocked tasks within the team context."
    >
      <EmptyState
        title="No workload data yet"
        description="This view will show your own workload snapshot once connected to real data. Task editing stays owned by Inbox."
      />
    </PageContainer>
  )
}
