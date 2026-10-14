'use client';

import { useEffect, useState, type ReactNode } from 'react';

// Opt-in: MSW only runs when NEXT_PUBLIC_API_MOCKING=enabled (see .env.example).
// Next.js inlines NEXT_PUBLIC_* values at build time, so in any build without
// the variable this is the constant `false`.
const MOCKING_ENABLED = process.env.NEXT_PUBLIC_API_MOCKING === 'enabled';

// Module-scoped, not component state: React StrictMode's dev-only
// mount/cleanup/mount runs this effect twice in immediate succession, and
// both invocations would otherwise call worker.start() concurrently on the
// same worker - the second call throws "cannot configure an already
// enabled network" because the interceptor is already mid-enable from the
// first. Caching the in-flight/resolved start promise here means every
// effect run (both StrictMode passes, and any real remount) shares the
// same single start() call. It naturally resets on a real page navigation,
// since that re-evaluates this module from scratch.
let startPromise: Promise<void> | null = null;

/**
 * Starts the MSW browser worker (mocks/browser.ts) before rendering
 * `children` - only when mocking is enabled, and a no-op everywhere else.
 *
 * Why gate rendering on it (not just "start it and render children
 * immediately"): registering a Service Worker is asynchronous. If children
 * rendered right away, their own effects could fire a fetch() before the
 * worker has finished activating, and that first request would hit the
 * real network instead of being mocked. Blocking on `ready` means every
 * fetch made by this app only happens after interception is live.
 *
 * The dynamic import() (rather than a static import of mocks/browser at
 * the top of this file) keeps MSW's code out of the production bundle:
 * with mocking off, the effect returns before the import is ever requested.
 */
export function MockingProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(!MOCKING_ENABLED);

  useEffect(() => {
    if (!MOCKING_ENABLED) return;

    let cancelled = false;

    if (!startPromise) {
      startPromise = import('@/mocks/browser').then(({ worker }) =>
        worker
          .start({
            // Anything without a matching handler (Next.js's own dev
            // requests, and the real /api/workspace/* and /api/manager/*
            // calls) passes straight through to the Gateway, instead of
            // being logged as a warning or blocked as an error.
            onUnhandledRequest: 'bypass',
          })
          .then(() => undefined),
      );
    }
    const started = startPromise;

    started.then(() => {
      if (!cancelled) setReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!ready) return null;

  return <>{children}</>;
}
