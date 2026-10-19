/**
 * Auth API — sign-in with a username and password. The Gateway doesn't expose
 * `POST /api/auth/login` yet, so in local development it's answered by MSW in
 * the browser (mocks/handlers.ts, on when NEXT_PUBLIC_API_MOCKING=enabled).
 * This call already has its final shape: once the Gateway serves the endpoint,
 * only the mock handler goes away.
 */
import { api } from './api';

export type AuthRole = 'manager' | 'employee';

export interface LoginRequest {
  username: string;
  password: string;
}

/** The signed-in user. */
export interface LoginResponse {
  id: string;
  displayName: string;
  role: AuthRole;
}

/** Rejects with an ApiError (status 401) when the username or password is wrong. */
export function login(credentials: LoginRequest): Promise<LoginResponse> {
  return api.post<LoginResponse>('/api/auth/login', credentials);
}
