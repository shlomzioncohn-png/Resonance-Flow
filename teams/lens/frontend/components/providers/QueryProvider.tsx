'use client';

import { useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { ApiError } from '@/services/api';

const MAX_RETRIES = 2;

/**
 * 4xx means the request itself is wrong (not found, forbidden...), so retrying can't fix it.
 * Anything else (5xx, network failure) may be temporary, so retry up to MAX_RETRIES times.
 * `failureCount` is 0 on the first failure.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < MAX_RETRIES;
}

export function QueryProvider({ children }: { children: ReactNode }) {
  // useState keeps one client per browser load; a plain `new` would make a new, empty cache every render
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Data counts as fresh for 30s: page-to-page navigation reuses it without refetching
            staleTime: 30_000,
            retry: shouldRetry,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {process.env.NODE_ENV === 'development' && <ReactQueryDevtools />}
    </QueryClientProvider>
  );
}
