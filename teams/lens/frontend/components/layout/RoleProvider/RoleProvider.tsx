'use client'

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'

export type Role = 'manager' | 'employee' | null

interface RoleContextValue {
  role: Role
  setRole: (role: Role) => void
}

const STORAGE_KEY = 'lens.role'

const RoleContext = createContext<RoleContextValue | null>(null)

export function RoleProvider({ children }: { children: ReactNode }) {
  const [role, setRoleState] = useState<Role>(null)

  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY)
      if (stored === 'manager' || stored === 'employee') setRoleState(stored)
    } catch {
      // localStorage unavailable — fall back to the in-memory default (no role)
    }
  }, [])

  const setRole = (next: Role) => {
    setRoleState(next)
    try {
      if (next) window.localStorage.setItem(STORAGE_KEY, next)
      else window.localStorage.removeItem(STORAGE_KEY)
    } catch {
      // localStorage unavailable — the in-memory state still updates for this session
    }
  }

  return <RoleContext.Provider value={{ role, setRole }}>{children}</RoleContext.Provider>
}

export function useRole() {
  const ctx = useContext(RoleContext)
  if (!ctx) throw new Error('useRole must be used within a RoleProvider')
  return ctx
}
