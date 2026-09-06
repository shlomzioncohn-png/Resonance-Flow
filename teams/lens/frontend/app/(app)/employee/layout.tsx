import type { ReactNode } from 'react'
import { TaskProvider } from './taskStore'

export default function EmployeeLayout({ children }: { children: ReactNode }) {
  return <TaskProvider>{children}</TaskProvider>
}
