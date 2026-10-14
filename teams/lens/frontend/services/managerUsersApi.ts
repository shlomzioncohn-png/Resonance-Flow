/**
 * Manager Users API — typed wrapper around the Gateway's /api/manager/users
 * endpoints (manager-users-screen-task.md). Mirrors the BFF/Gateway
 * UserRowDTO/UserDetailsDTO/UserListResponseDTO in
 * teams/lens/{bff,gateway}/src/main/java/com/rnd/{bff,gateway}/dto/.
 *
 * Real call all the way down: Gateway → BFF → Inbox's canonical `users`
 * table (see com.bff.client.InboxUserClient's javadoc) — not mock data.
 * MANAGER-only: Gateway enforces hasRole("MANAGER") once a real token is
 * sent (Auth stage 3 — see api.ts). Until then, every request is open, same
 * as every other /api/** route today.
 */
import { api, ApiError } from './api';

export interface UserRow {
  id: string;
  displayName: string;
  email: string;
  /** LENS role: MANAGER | EMPLOYEE */
  role: string;
  /** PENDING | ACTIVE | SUSPENDED | DISABLED */
  status: string;
  lastLoginAt?: string;
  /** True on the acting manager's own row — set by the BFF, which knows who is calling. */
  currentUser?: boolean;
}

export interface UserDetails extends UserRow {
  firstName?: string;
  lastName?: string;
  phone?: string;
  username: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UserListParams {
  page?: number;
  size?: number;
  /** e.g. "displayName,asc" */
  sort?: string;
}

export interface UserListResponse {
  items: UserRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export function listUsers(params: UserListParams = {}): Promise<UserListResponse> {
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.size !== undefined) query.set('size', String(params.size));
  if (params.sort) query.set('sort', params.sort);

  const queryString = query.toString();
  return api.get<UserListResponse>(`/api/manager/users${queryString ? `?${queryString}` : ''}`);
}

export function getUser(id: string): Promise<UserDetails> {
  return api.get<UserDetails>(`/api/manager/users/${id}`);
}

/** Only changed fields belong in `fields` — the caller decides what changed, this just forwards it. */
export interface UserEditFields {
  displayName?: string;
  firstName?: string;
  lastName?: string;
  phone?: string;
}

export function updateUser(id: string, fields: UserEditFields): Promise<UserDetails> {
  return api.patch<UserDetails>(`/api/manager/users/${id}`, fields);
}

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | 'DISABLED';

export function updateUserStatus(id: string, status: UserStatus): Promise<UserDetails> {
  return api.patch<UserDetails>(`/api/manager/users/${id}/status`, { status });
}

export { ApiError };
