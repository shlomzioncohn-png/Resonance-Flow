import { useQuery } from '@tanstack/react-query';
import { getManagerKpis, type ManagerKpiFilters } from '@/services/managerApi';
import { kpiKeys } from '@/services/queryKeys';

/**
 * KPI tiles for the Manager Dashboard (in progress, blocked, delayed, average
 * blocked time), for the manager's whole tenant. Comes through the Gateway
 * from the BFF, which counts Inbox's tasks. Retries follow the QueryProvider defaults.
 */
export function useManagerKpis(filters: ManagerKpiFilters = {}) {
  return useQuery({
    queryKey: kpiKeys.list(filters),
    queryFn: () => getManagerKpis(filters),
  });
}
