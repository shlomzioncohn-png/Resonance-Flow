import type { ReactNode } from 'react';
import SupervisorAccountOutlinedIcon from '@mui/icons-material/SupervisorAccountOutlined';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined';
import type { AuthRole } from '@/services/authApi';

type NavItem = {
  label: string;
  href: string;
  icon?: ReactNode;
};

type NavSection = {
  label: string;
  /** Only these roles see the section - and may open its screens. */
  roles: AuthRole[];
  items: NavItem[];
};

const MANAGER_ROLES: AuthRole[] = ['TENANT_ADMIN', 'TEAM_LEAD'];

export const navSections: NavSection[] = [
  {
    label: 'Manager',
    roles: MANAGER_ROLES,
    items: [
      {
        label: 'Dashboard',
        href: '/manager',
        icon: <SupervisorAccountOutlinedIcon fontSize="small" />,
      },
      { label: 'Projects', href: '/manager/projects' },
      { label: 'Work in progress', href: '/manager/wip' },
      { label: 'Blocked & delayed', href: '/manager/blocked' },
      { label: 'Bottlenecks', href: '/manager/bottlenecks' },
      { label: 'Users', href: '/manager/users' },
    ],
  },
  {
    label: 'Employee',
    roles: ['EMPLOYEE'],
    items: [
      { label: 'My Workspace', href: '/employee', icon: <PersonOutlineIcon fontSize="small" /> },
      { label: 'My Tasks', href: '/employee/tasks' },
    ],
  },
];

/** The screen a role lands on after signing in. */
export function homeFor(role: AuthRole): string {
  return MANAGER_ROLES.includes(role) ? '/manager' : '/employee';
}

/** Path prefixes each role may open. '/' only redirects to the role's home. */
const ALLOWED_PATHS: Record<AuthRole, string[]> = {
  TENANT_ADMIN: ['/manager', '/team'],
  TEAM_LEAD: ['/manager', '/team'],
  EMPLOYEE: ['/employee'],
};

export function canAccess(role: AuthRole, pathname: string): boolean {
  if (pathname === '/') return true;
  return ALLOWED_PATHS[role].some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}
