import PageContainer from '@/components/layout/PageContainer'
import EmptyState from '@/components/feedback/EmptyState'

export default async function ProjectDetailPage({
  params,
}: {
  params: Promise<{ projectId: string }>
}) {
  const { projectId } = await params

  return (
    <PageContainer
      title={`Project: ${projectId}`}
      description="Workload and status detail for this project. Reached from Manager Dashboard or Manager → Workload."
    >
      <EmptyState
        title="No detail data yet"
        description={`Workload and status detail for project "${projectId}" will appear here once connected to real data.`}
      />
    </PageContainer>
  )
}
