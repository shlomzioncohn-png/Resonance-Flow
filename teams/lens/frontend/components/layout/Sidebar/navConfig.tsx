import type { ReactNode } from 'react'
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined'
import SupervisorAccountOutlinedIcon from '@mui/icons-material/SupervisorAccountOutlined'
import PersonOutlineIcon from '@mui/icons-material/PersonOutlined'

export interface NavItem {
  label: string
  href: string
  icon?: ReactNode
}

export interface NavSection {
  label: string
  items: NavItem[]
}

export const navSections: NavSection[] = [
  {
    label: 'Overview',
    items: [{ label: 'Dashboard', href: '/', icon: <DashboardOutlinedIcon fontSize="small" /> }],
  },
  {
    label: 'Manager',
    items: [
      {
        label: 'Manager Dashboard',
        href: '/manager',
        icon: <SupervisorAccountOutlinedIcon fontSize="small" />,
      },
      { label: 'Workload', href: '/manager/workload' },
      { label: 'Work in progress', href: '/manager/wip' },
      { label: 'Blocked & delayed', href: '/manager/blocked' },
      { label: 'Bottlenecks', href: '/manager/bottlenecks' },
    ],
  },
  {
    label: 'Employee',
    items: [
      { label: 'Employee overview', href: '/employee', icon: <PersonOutlineIcon fontSize="small" /> },
      { label: 'My workload', href: '/employee/workload' },
      { label: 'My blocked work', href: '/employee/blocked' },
    ],
  },
]
