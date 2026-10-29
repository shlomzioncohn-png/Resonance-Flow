import PageContainer from '@/components/layout/PageContainer';
import EmptyState from '@/components/feedback/EmptyState';

export default async function TeamMemberPage({
  params,
}: {
  params: Promise<{ employeeId: string }>;
}) {
  const { employeeId } = await params;

  return (
    <PageContainer
      title={`Team member: ${employeeId}`}
      description="Blocker detail for this team member."
    >
      <EmptyState
        title="No detail data yet"
        description={`Blocker detail for employee "${employeeId}" will appear here once connected to real data.`}
      />
    </PageContainer>
  );
}
