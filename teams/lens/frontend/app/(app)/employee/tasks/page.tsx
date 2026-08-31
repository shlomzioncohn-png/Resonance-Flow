import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default function EmployeeTasksPage() {
  return (
    <PageContainer
      title="My Tasks"
      description="Your complete task list — status, due dates, and everything you own."
    >
      <EmptyState
        title="Task list coming soon"
        description="This view will list every task assigned to you, matching the approved employee UI design. Selecting a task will open its full details for editing."
      />
    </PageContainer>
  )
}
