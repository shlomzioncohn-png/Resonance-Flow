import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default function EmployeeBlockedPage() {
  return (
    <PageContainer
      title="My blocked work"
      description="Tasks assigned to you that are currently blocked or delayed."
    >
      <EmptyState
        title="No blocked work reported"
        description="This view will surface your blocked or delayed tasks once connected to real data."
      />
    </PageContainer>
  )
}
