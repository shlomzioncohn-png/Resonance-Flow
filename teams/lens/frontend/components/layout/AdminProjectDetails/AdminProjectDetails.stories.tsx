import type { Meta, StoryObj } from '@storybook/react'
import AdminProjectDetails from './AdminProjectDetails'

const meta = {
  title: 'Admin/ProjectDetails',
  component: AdminProjectDetails,
  parameters: {
    layout: 'fullscreen',
    docs: {
      canvas: {
        height: '2000px',
      },
    },
  },
  tags: ['autodocs'],
} satisfies Meta<typeof AdminProjectDetails>

export default meta
type Story = StoryObj<typeof meta>

const mockTeam = [
  {
    id: '1',
    name: 'Jordan Martinez',
    role: 'Tech Lead',
    avatar: 'https://i.pravatar.cc/150?img=1',
    activeTasks: 8,
    workload: 'At Capacity' as const,
  },
  {
    id: '2',
    name: 'Priya Sharma',
    role: 'Senior Engineer',
    avatar: 'https://i.pravatar.cc/150?img=3',
    activeTasks: 5,
    workload: 'Available' as const,
  },
  {
    id: '3',
    name: 'Alex Thompson',
    role: 'Backend Engineer',
    avatar: 'https://i.pravatar.cc/150?img=5',
    activeTasks: 11,
    workload: 'Overloaded' as const,
  },
  {
    id: '4',
    name: 'Sara Lin',
    role: 'Frontend Engineer',
    avatar: 'https://i.pravatar.cc/150?img=4',
    activeTasks: 8,
    workload: 'Available' as const,
  },
  {
    id: '5',
    name: 'Kai Rosario',
    role: 'QA Engineer',
    avatar: 'https://i.pravatar.cc/150?img=6',
    activeTasks: 9,
    workload: 'At Capacity' as const,
  },
  {
    id: '6',
    name: 'Mei Chen',
    role: 'DevOps Engineer',
    avatar: 'https://i.pravatar.cc/150?img=7',
    activeTasks: 4,
    workload: 'Available' as const,
  },
]

const mockProcessStages = [
  { name: 'Planning', count: 12 },
  { name: 'In Progress', count: 18 },
  { name: 'Review', count: 8 },
  { name: 'Testing', count: 6 },
  { name: 'Done', count: 20 },
]

const mockActivities = [
  {
    id: '1',
    actor: { name: 'Jordan Martinez', avatar: 'https://i.pravatar.cc/150?img=1' },
    action: 'completed',
    description: '"User auth refactor"',
    timestamp: '2 min ago',
    type: 'success' as const,
  },
  {
    id: '2',
    actor: { name: 'System', avatar: 'https://i.pravatar.cc/150?img=0' },
    action: 'changed',
    description: 'project status to At Risk',
    timestamp: '20 min ago',
    type: 'warning' as const,
  },
  {
    id: '3',
    actor: { name: 'Priya Sharma', avatar: 'https://i.pravatar.cc/150?img=3' },
    action: 'commented',
    description: 'on "API migration task"',
    timestamp: '2h ago',
  },
  {
    id: '4',
    actor: { name: 'Jordan Martinez', avatar: 'https://i.pravatar.cc/150?img=1' },
    action: 'assigned',
    description: '"E2E test suite" to Kai R.',
    timestamp: '5h ago',
  },
  {
    id: '5',
    actor: { name: 'Alex Thompson', avatar: 'https://i.pravatar.cc/150?img=5' },
    action: 'created',
    description: '"Deploy staging v2.1.4"',
    timestamp: '5h ago',
  },
]

/**
 * Default healthy project with good progress
 */
export const Healthy: Story = {
  args: {
    projectId: 'proj-001',
    projectName: 'Project Phoenix',
    description: 'UI redesign for main product — process, team, and blockers',
    status: 'healthy',
    startDate: 'Mar 10, 2025',
    endDate: 'Sep 30, 2025',
    totalTasks: 64,
    inProgressTasks: 18,
    blockedTasks: 5,
    completedTasks: 20,
    progressPercentage: 48,
    processStages: mockProcessStages,
    team: mockTeam,
    recentActivities: mockActivities,
    onEdit: () => alert('Edit clicked'),
    onArchive: () => alert('Archive clicked'),
    onDelete: () => alert('Delete clicked'),
  },
}

/**
 * Project at risk with moderate progress
 */
export const AtRisk: Story = {
  args: {
    projectId: 'proj-002',
    projectName: 'Search Relevance API',
    description: 'Implementing machine learning-based search to improve product discovery.',
    status: 'warning',
    startDate: 'Jan 15, 2025',
    endDate: 'Aug 15, 2025',
    totalTasks: 32,
    inProgressTasks: 8,
    blockedTasks: 3,
    completedTasks: 14,
    progressPercentage: 44,
    processStages: [
      { name: 'Planning', count: 2 },
      { name: 'In Progress', count: 8 },
      { name: 'Review', count: 3 },
      { name: 'Testing', count: 5 },
      { name: 'Done', count: 14 },
    ],
    team: mockTeam.slice(0, 3),
    recentActivities: mockActivities.slice(0, 3),
    onEdit: () => alert('Edit clicked'),
    onArchive: () => alert('Archive clicked'),
    onDelete: () => alert('Delete clicked'),
  },
}

/**
 * Critical project requiring immediate attention
 */
export const Critical: Story = {
  args: {
    projectId: 'proj-003',
    projectName: 'Payment Gateway Migration',
    description: 'Migrating from legacy payment processor to modern API with enhanced security.',
    status: 'critical',
    startDate: 'Feb 1, 2025',
    endDate: 'May 1, 2025',
    totalTasks: 18,
    inProgressTasks: 3,
    blockedTasks: 4,
    completedTasks: 3,
    progressPercentage: 17,
    processStages: [
      { name: 'Planning', count: 4 },
      { name: 'In Progress', count: 3 },
      { name: 'Review', count: 0 },
      { name: 'Testing', count: 0 },
      { name: 'Done', count: 3 },
    ],
    team: mockTeam.slice(0, 4),
    recentActivities: mockActivities,
    onEdit: () => alert('Edit clicked'),
    onArchive: () => alert('Archive clicked'),
    onDelete: () => alert('Delete clicked'),
  },
}
