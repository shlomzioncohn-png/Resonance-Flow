'use client';

import PageContainer from '@/components/layout/PageContainer';
import DataTable, { type Column } from '@/components/ui/DataTable';
import { getErrorMessage } from '@/services/api';
import type { WorkloadEntry } from '@/services/managerApi';
import { useManagerWorkload } from '@/hooks/useManagerWorkload';

const columns: Column<WorkloadEntry>[] = [
  { id: 'employee', header: 'Employee', render: (entry) => entry.employeeName },
  {
    id: 'activeTasks',
    header: 'Active tasks',
    align: 'right',
    render: (entry) => entry.activeTasks,
  },
];

export default function ManagerWorkloadPage() {
  const { data, isPending, isError, error, refetch } = useManagerWorkload();

  return (
    <PageContainer
      title="Team workload"
      description="Per-employee and per-project breakdown of active work."
    >
      <DataTable
        ariaLabel="Team workload"
        columns={columns}
        rows={data ?? []}
        getRowId={(entry) => entry.employeeId}
        loading={isPending}
        error={isError ? getErrorMessage(error) : null}
        onRetry={() => refetch()}
        emptyTitle="No workload data yet"
        emptyDescription="Workload will appear here once Inbox and Chain provide it."
      />
    </PageContainer>
  );
}
