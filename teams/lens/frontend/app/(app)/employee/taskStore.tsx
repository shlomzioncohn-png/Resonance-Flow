'use client'

import { createContext, useContext, useState, type ReactNode } from 'react'
import { INITIAL_TASK, type EmployeeTask } from './mockTask'

interface TaskContextValue {
  task: EmployeeTask
  /** Clears the task's blocking dependency — the one state change the chat demo can make. */
  clearBlockedBy: () => void
}

const TaskContext = createContext<TaskContextValue | null>(null)

export function TaskProvider({ children }: { children: ReactNode }) {
  const [task, setTask] = useState<EmployeeTask>(INITIAL_TASK)

  const clearBlockedBy = () =>
    setTask((current) => ({
      ...current,
      blockedBy: [],
      blockingReason: null,
      status: 'Ready',
    }))

  return <TaskContext.Provider value={{ task, clearBlockedBy }}>{children}</TaskContext.Provider>
}

export function useTask() {
  const ctx = useContext(TaskContext)
  if (!ctx) throw new Error('useTask must be used within a TaskProvider')
  return ctx
}
