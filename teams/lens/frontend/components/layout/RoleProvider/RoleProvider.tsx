'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { AuthRole, LoginResponse } from '@/services/authApi';

type Role = AuthRole | null;

type SignedInUser = LoginResponse['user'];

type RoleContextValue = {
  user: SignedInUser | null;
  role: Role;
  /** False until the stored user has been read - don't redirect before that. */
  ready: boolean;
  signIn: (user: SignedInUser) => void;
  signOut: () => void;
};

const RoleContext = createContext<RoleContextValue | null>(null);

// sessionStorage, not memory: typing a URL or refreshing reloads the page, and a
// role kept only in state was lost - the app then showed every screen to everyone.
// Only the name and role are stored here, never the token (that stays in the
// Gateway's HttpOnly cookie). The tab forgets it when it's closed.
export const USER_STORAGE_KEY = 'lens.user';

const ROLES: AuthRole[] = ['EMPLOYEE', 'TENANT_ADMIN', 'TEAM_LEAD'];

function readStoredUser(): SignedInUser | null {
  try {
    const stored = window.sessionStorage.getItem(USER_STORAGE_KEY);
    if (!stored) return null;
    const user = JSON.parse(stored) as SignedInUser;
    return ROLES.includes(user.role) ? user : null;
  } catch {
    // sessionStorage unavailable or the value is broken - treat as signed out
    return null;
  }
}

export function RoleProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SignedInUser | null>(null);
  const [ready, setReady] = useState(false);

  // Read in an effect, not in useState(): the server render has no sessionStorage,
  // and the first client render must match it.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setUser(readStoredUser());
    setReady(true);
  }, []);

  const signIn = (signedIn: SignedInUser) => {
    setUser(signedIn);
    try {
      window.sessionStorage.setItem(USER_STORAGE_KEY, JSON.stringify(signedIn));
    } catch {
      // Storage unavailable - the user stays signed in until the page reloads
    }
  };

  const signOut = () => {
    setUser(null);
    try {
      window.sessionStorage.removeItem(USER_STORAGE_KEY);
    } catch {
      // Nothing was stored
    }
  };

  return (
    <RoleContext.Provider value={{ user, role: user?.role ?? null, ready, signIn, signOut }}>
      {children}
    </RoleContext.Provider>
  );
}

export function useRole() {
  const ctx = useContext(RoleContext);
  if (!ctx) throw new Error('useRole must be used within a RoleProvider');
  return ctx;
}
