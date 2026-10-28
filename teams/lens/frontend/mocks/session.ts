/**
 * For stories: start the story signed in. RoleProvider reads the user from
 * sessionStorage when it mounts, so the user is stored before the story
 * renders, and removed after it so it never leaks into the next story.
 *
 * Usage: `beforeEach: signedInAs('EMPLOYEE')`
 */
import { USER_STORAGE_KEY } from '@/components/layout/RoleProvider';
import type { AuthRole } from '@/services/authApi';

const DISPLAY_NAMES: Record<AuthRole, string> = {
  TENANT_ADMIN: 'Tara Admin',
  TEAM_LEAD: 'Maya Manager',
  EMPLOYEE: 'Eli Employee',
};

export const signedInAs = (role: AuthRole) => () => {
  window.sessionStorage.setItem(
    USER_STORAGE_KEY,
    JSON.stringify({ id: `story-${role}`, displayName: DISPLAY_NAMES[role], role }),
  );
  return () => window.sessionStorage.removeItem(USER_STORAGE_KEY);
};
