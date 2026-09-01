/**
 * Base client for calling the LENS Gateway — the frontend's single entry point per
 * teams/lens/gateway/GATEWAY_CONTRACT.md. Never call BFF or any other team's service
 * directly from the frontend; everything goes through the Gateway.
 *
 * Nothing under app/ imports this yet. As of 2026-09-01 the Gateway↔BFF wiring itself
 * has known bugs (wrong BFF hostname/port/path — see docs/api-integration-status.md),
 * so there is nothing real to connect to. This client is prep work: once that's fixed,
 * swapping a mock adapter for a real one is a small, local change instead of a rewrite.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'

/** Error shape the Gateway documents for every 4xx/5xx response (GATEWAY_CONTRACT.md §3-4). */
export interface GatewayErrorBody {
  status: number
  error: string
  message: string
  path: string
  timestamp: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly path: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })

  if (!response.ok) {
    const body: GatewayErrorBody | null = await response.json().catch(() => null)
    throw new ApiError(body?.message ?? response.statusText, response.status, path)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PATCH', body: body ? JSON.stringify(body) : undefined }),
}
