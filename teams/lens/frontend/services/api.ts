/**
 * Base client for calling the LENS Gateway — the frontend's single entry point per
 * teams/lens/gateway/GATEWAY_CONTRACT.md. Never call BFF or any other team's service
 * directly from the frontend; everything goes through the Gateway.
 *
 * Used by services/*Api.ts. See docs/api-integration-status.md for what's real and
 * what's still mocked behind the Gateway.
 */

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** Error shape the Gateway sends for every 4xx/5xx response (GATEWAY_CONTRACT.md §3-4). */
export interface GatewayErrorBody {
  status: 'ERROR';
  /** e.g. NOT_FOUND, FORBIDDEN, SERVICE_UNAVAILABLE */
  errorCode: string;
  message: string;
  path: string;
  timestamp: string;
  details?: string;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly path: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * Turns any error from an API call into a short message a user can act on.
 * The Gateway's own messages are technical ("Downstream service returned an error"),
 * so the HTTP status decides the text instead.
 */
export function getErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    // fetch() throws a TypeError when the Gateway can't be reached at all
    return 'The task service is unreachable. Check your connection and try again.';
  }
  switch (error.status) {
    case 400:
      return "This change isn't allowed for this task.";
    case 401:
      return "We couldn't verify who you are. Please sign in again.";
    case 403:
      return "You don't have access to this task.";
    case 404:
      return "This task wasn't found. It may have been removed.";
    case 502:
    case 503:
    case 504:
      return 'The task service is temporarily unavailable. Please try again in a moment.';
    default:
      return 'Something went wrong. Please try again.';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (!response.ok) {
    const body: GatewayErrorBody | null = await response.json().catch(() => null);
    throw new ApiError(body?.message ?? response.statusText, response.status, path);
  }

  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PATCH', body: body ? JSON.stringify(body) : undefined }),
};
