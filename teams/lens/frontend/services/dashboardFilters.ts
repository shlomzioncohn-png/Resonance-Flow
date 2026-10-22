/**
 * The one filter model of the manager dashboard. Every dashboard tile, the URL
 * and the React Query keys work only with DashboardFilters — a new filter is a
 * new field in the schema below, and every tile receives it.
 *
 * The same schema will validate filters parsed from free text by the LLM
 * (POST /manager/dashboard/filters/parse in the BFF), so manual and LLM filters
 * go through the exact same checks.
 */
import { z } from 'zod';

const dashboardFiltersSchema = z
  .object({
    period: z.enum(['7d', '30d', '90d']),
    projectIds: z.array(z.string().min(1)).optional(),
    // future: status, tier, onlyBlocked, deadlineWithin...
  })
  .strict();

export type DashboardFilters = z.infer<typeof dashboardFiltersSchema>;

export const DEFAULT_DASHBOARD_FILTERS: DashboardFilters = { period: '30d' };

const PERIOD_PARAM = 'period';
const PROJECT_PARAM = 'project';

/**
 * Applies `partial` on top of `current` and validates the result. Returns null
 * when anything is invalid, so the caller leaves the URL as it is. An empty
 * project list means "all projects" and is stored as no list at all.
 */
export function mergeDashboardFilters(
  current: DashboardFilters,
  partial: Partial<DashboardFilters>,
): DashboardFilters | null {
  const result = dashboardFiltersSchema.safeParse({ ...current, ...partial });
  if (!result.success) return null;

  const { projectIds, ...rest } = result.data;
  const uniqueIds = Array.from(new Set(projectIds ?? []));
  return uniqueIds.length > 0 ? { ...rest, projectIds: uniqueIds } : rest;
}

/** Reads the filters from the URL. A missing or invalid value falls back to its default. */
export function filtersFromSearchParams(params: URLSearchParams): DashboardFilters {
  const period = dashboardFiltersSchema.shape.period.safeParse(params.get(PERIOD_PARAM));
  const projectIds = params.getAll(PROJECT_PARAM).filter(Boolean);

  return (
    mergeDashboardFilters(DEFAULT_DASHBOARD_FILTERS, {
      period: period.success ? period.data : DEFAULT_DASHBOARD_FILTERS.period,
      projectIds,
    }) ?? DEFAULT_DASHBOARD_FILTERS
  );
}

/** Writes the filters as `?period=30d&project=p-1&project=p-2`. */
export function filtersToSearchParams(filters: DashboardFilters): URLSearchParams {
  const params = new URLSearchParams();
  params.set(PERIOD_PARAM, filters.period);
  filters.projectIds?.forEach((id) => params.append(PROJECT_PARAM, id));
  return params;
}
