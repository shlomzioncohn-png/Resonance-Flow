/**
 * Auth API — sign-in with email and password via the LENS Gateway.
 * Gateway serves POST /auth/login (see teams/lens/gateway/GATEWAY_CONTRACT.md).
 * 
 * The Gateway validates credentials against Inbox and returns user info + token.
 * JWT token is sent ONLY via HttpOnly Set-Cookie header (not in response body).
 * Frontend should NOT attempt to extract the token — the browser manages it automatically.
 */
import { api } from './api';

export type AuthRole = 'EMPLOYEE' | 'TENANT_ADMIN' | 'TEAM_LEAD';

export interface LoginRequest {
  login: string;  // email address
  password: string;
}

/** The signed-in user information returned after successful login. */
export interface LoginResponse {
  expiresIn: number;    // Token expiry in seconds
  user: {
    id: string;
    displayName: string;
    role: AuthRole;
  };
}

/** Rejects with an ApiError (status 401) when the login or password is wrong. */
export function login(credentials: LoginRequest): Promise<LoginResponse> {
  return api.post<LoginResponse>('/auth/login', credentials);
}

