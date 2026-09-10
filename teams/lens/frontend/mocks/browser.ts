/**
 * The browser-side MSW worker: registers a real Service Worker
 * (public/mockServiceWorker.js) that intercepts fetch() calls made by this
 * page and answers matching ones with `handlers` below. Started by
 * components/layout/MockingProvider - see that file for why start() has to
 * be awaited before the rest of the app renders.
 */
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

export const worker = setupWorker(...handlers)
