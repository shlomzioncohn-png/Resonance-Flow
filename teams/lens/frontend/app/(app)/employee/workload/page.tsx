import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default function EmployeeWorkloadPage() {
  return (
    <PageContainer
      title="My workload"
      description="Your own assigned tasks and load — a read-only aggregate view, not a task-management inbox."
    >
      <EmptyState
        title="No workload data yet"
        description="This view will list your assigned tasks and load once connected to real data."
      />
    </PageContainer>
  )
}
