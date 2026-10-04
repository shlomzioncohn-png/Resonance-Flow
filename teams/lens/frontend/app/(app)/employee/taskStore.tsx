'use client';

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import LoadingState from '@/components/feedback/LoadingState';
import ErrorState from '@/components/feedback/ErrorState';
import { getMyTask, type EmployeeTask } from '@/services/employeeApi';

interface TaskContextValue {
  task: EmployeeTask;
  /** Clears the task's blocking dependency — the one state change the chat demo can make. */
  clearBlockedBy: () => void;
}

const TaskContext = createContext<TaskContextValue | null>(null);

export function TaskProvider({ children }: { children: ReactNode }) {
  const [task, setTask] = useState<EmployeeTask | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // ignore guards against a stale response landing after this provider
    // unmounts (e.g. the user navigates away while the fetch is in flight)
    // - without it, a late .then() could still call setState on an
    // unmounted provider's closure.
    let ignore = false;
    getMyTask()
      .then((result) => {
        if (!ignore) setTask(result);
      })
      .catch((err) => {
        if (!ignore) setError(err instanceof Error ? err.message : 'Failed to load task');
      });
    return () => {
      ignore = true;
    };
  }, []);

  const clearBlockedBy = () =>
    setTask(
      (current) =>
        current && {
          ...current,
          blockedBy: [],
          blockingReason: null,
          status: 'Ready',
        },
    );

  if (error) {
    return <ErrorState description={error} />;
  }

  if (!task) {
    return <LoadingState message="Loading your task…" />;
  }

  return <TaskContext.Provider value={{ task, clearBlockedBy }}>{children}</TaskContext.Provider>;
}

export function useTask() {
  const ctx = useContext(TaskContext);
  if (!ctx) throw new Error('useTask must be used within a TaskProvider');
  return ctx;
}
