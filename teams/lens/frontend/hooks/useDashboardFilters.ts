import { useCallback, useMemo } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import {
  filtersFromSearchParams,
  filtersToSearchParams,
  mergeDashboardFilters,
  type DashboardFilters,
} from '@/services/dashboardFilters';

/**
 * The dashboard filters live in the URL, so a filtered view can be shared and
 * survives a refresh. `applyFilters` is the only way to change them — the
 * manual chips use it now, and the LLM "Ask" field will use it later.
 *
 * It returns false and leaves the URL untouched when the result is invalid.
 *
 * Uses `useSearchParams`, so the component calling it must be inside a
 * `<Suspense>` boundary for the production build.
 */
export function useDashboardFilters() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const filters = useMemo(
    () => filtersFromSearchParams(new URLSearchParams(searchParams.toString())),
    [searchParams],
  );

  const applyFilters = useCallback(
    (partial: Partial<DashboardFilters>) => {
      const next = mergeDashboardFilters(filters, partial);
      if (!next) return false;

      router.replace(`${pathname}?${filtersToSearchParams(next)}`, { scroll: false });
      return true;
    },
    [filters, pathname, router],
  );

  return { filters, applyFilters };
}
