'use client'

import { useEffect, useState, type ReactNode } from 'react'

/**
 * Starts the MSW browser worker (mocks/browser.ts) before rendering
 * `children` - development only, and a no-op everywhere else.
 *
 * Why gate rendering on it (not just "start it and render children
 * immediately"): registering a Service Worker is asynchronous. If children
 * rendered right away, their own effects could fire a fetch() before the
 * worker has finished activating, and that first request would hit the
 * real network instead of being mocked - a real, hard-to-reproduce race,
 * not a hypothetical one. Blocking on `ready` means every fetch made by
 * this app only ever happens after interception is guaranteed live.
 *
 * The dynamic import() (rather than a static import of mocks/browser at
 * the top of this file) keeps MSW's code out of the production bundle
 * entirely: in production the `if` below returns before the import is ever
 * requested, so nothing pulls it in.
 */
export function MockingProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(process.env.NODE_ENV !== 'development')

  useEffect(() => {
    if (process.env.NODE_ENV !== 'development') return

    let cancelled = false
    import('@/mocks/browser').then(({ worker }) =>
      worker
        .start({
          // Anything without a matching handler (Next.js's own dev
          // requests, and the real, already-working /api/workspace/*
          // calls) passes straight through untouched, instead of being
          // logged as a warning or blocked as an error.
          onUnhandledRequest: 'bypass',
        })
        .then(() => {
          if (!cancelled) setReady(true)
        }),
    )
    return () => {
      cancelled = true
    }
  }, [])

  if (!ready) return null

  return <>{children}</>
}
