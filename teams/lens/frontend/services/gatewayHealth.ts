import { api } from './api'

/** Shape of the Gateway's own /health response (GATEWAY_CONTRACT.md §5). */
export interface GatewayHealth {
  status: string
  service: string
  version: string
  timestamp: string
  dependencies?: {
    bff: 'UP' | 'DOWN' | 'UNKNOWN'
    bff_url: string
  }
}

/**
 * The Gateway's own health, not proxied through BFF — the best first thing to call once
 * a real Gateway is reachable, since it reports both "is the Gateway itself up" and
 * "is BFF reachable from the Gateway" (dependencies.bff) in one request, without needing
 * BFF's own status endpoint (whose current route is broken end to end — see
 * docs/api-integration-status.md).
 */
export function fetchGatewayHealth() {
  return api.get<GatewayHealth>('/health')
}
