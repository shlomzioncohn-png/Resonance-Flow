import { describe, expect, it } from 'vitest';
import {
  DEFAULT_DASHBOARD_FILTERS,
  filtersFromSearchParams,
  filtersToSearchParams,
  mergeDashboardFilters,
  type DashboardFilters,
} from '@/services/dashboardFilters';

const current: DashboardFilters = { period: '30d', projectIds: ['p-1'] };

describe('mergeDashboardFilters', () => {
  it('applies a valid partial on top of the current filters', () => {
    expect(mergeDashboardFilters(current, { period: '7d' })).toEqual({
      period: '7d',
      projectIds: ['p-1'],
    });
  });

  it.each([
    ['an unknown period', { period: '1y' }],
    ['an empty project id', { projectIds: [''] }],
    ['an unknown filter field', { onlyBlocked: true }],
  ])('rejects %s', (_name, partial) => {
    expect(mergeDashboardFilters(current, partial as Partial<DashboardFilters>)).toBeNull();
  });

  it('treats an empty project list as all projects', () => {
    expect(mergeDashboardFilters(current, { projectIds: [] })).toEqual({ period: '30d' });
  });

  it('drops duplicate project ids', () => {
    expect(mergeDashboardFilters(current, { projectIds: ['p-1', 'p-1', 'p-2'] })).toEqual({
      period: '30d',
      projectIds: ['p-1', 'p-2'],
    });
  });
});

describe('URL search params', () => {
  it('round-trips the filters through the URL', () => {
    const filters: DashboardFilters = { period: '90d', projectIds: ['p-1', 'p-2'] };
    const params = filtersToSearchParams(filters);

    expect(params.toString()).toBe('period=90d&project=p-1&project=p-2');
    expect(filtersFromSearchParams(params)).toEqual(filters);
  });

  it('falls back to the defaults for an empty URL', () => {
    expect(filtersFromSearchParams(new URLSearchParams())).toEqual(DEFAULT_DASHBOARD_FILTERS);
  });

  it('ignores an invalid period in the URL but keeps the valid projects', () => {
    expect(filtersFromSearchParams(new URLSearchParams('period=1y&project=p-2'))).toEqual({
      period: '30d',
      projectIds: ['p-2'],
    });
  });
});
