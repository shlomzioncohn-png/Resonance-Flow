/**
 * Users API — typed wrapper around the user list for the Manager's Users
 * screen. The Gateway doesn't expose `GET /api/users` yet, so in local
 * development it's answered by MSW in the browser (mocks/handlers.ts, on when
 * NEXT_PUBLIC_API_MOCKING=enabled). This call already has its final shape:
 * once the Gateway serves the endpoint, only the mock handler goes away.
 */
import { api } from './api';

export type UserRole = 'EMPLOYEE' | 'TEAM_LEAD' | 'TENANT_ADMIN';

export type UserStatus = 'ACTIVE' | 'PENDING' | 'SUSPENDED' | 'DISABLED';

/** Same field names as Inbox's user response. */
export interface User {
  id: string;
  displayName: string;
  email: string;
  role: UserRole;
  status: UserStatus;
  /** ISO-8601 timestamp; null for a user who has never signed in. */
  lastLoginAt: string | null;
}

export interface UsersPage {
  items: User[];
  /** Number of users across all pages. */
  total: number;
}

/** `page` is zero-based. */
export function getUsers(page = 0, size = 20): Promise<UsersPage> {
  return api.get<UsersPage>(`/api/users?page=${page}&size=${size}`);
}
