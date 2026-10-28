'use client';

import { createContext, useContext, useState, type ReactNode } from 'react';
import type { AuthRole } from '@/services/authApi';

export type Role = AuthRole | null;

interface RoleContextValue {
  role: Role;
  setRole: (role: Role) => void;
}

const RoleContext = createContext<RoleContextValue | null>(null);

export function RoleProvider({
  children,
  initialRole = null,
}: {
  children: ReactNode;
  /** Starting role - for stories/tests. The app always starts signed out (null). */
  initialRole?: Role;
}) {
  // Role stored ONLY in memory (state), NOT in localStorage.
  // Reason: localStorage is vulnerable to XSS attacks.
  // Role is set after successful login and is re-fetched from server if needed.
  // On page reload, role reverts to null and is re-established on next login.
  const [role, setRole] = useState<Role>(initialRole);

  return <RoleContext.Provider value={{ role, setRole }}>{children}</RoleContext.Provider>;
}

export function useRole() {
  const ctx = useContext(RoleContext);
  if (!ctx) throw new Error('useRole must be used within a RoleProvider');
  return ctx;
}
