import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default function ManagerDashboardPage() {
  return (
    <PageContainer
      title="Manager dashboard"
      description="Workload, work in progress, blocked work, bottlenecks, and support signals for your team."
    >
      <EmptyState
        title="Manager dashboard coming soon"
        description="This view will aggregate workload, WIP, blocked work, and bottlenecks once connected to real data."
      />
    </PageContainer>
  )
}
