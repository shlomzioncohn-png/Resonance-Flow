import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import ActivityFeed from './ActivityFeed';

const meta = {
  title: 'Admin/ActivityFeed',
  component: ActivityFeed,
  tags: ['autodocs'],
} satisfies Meta<typeof ActivityFeed>;

export default meta;
type Story = StoryObj<typeof meta>;

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
    timestamp: '20 ago',
    type: 'warning' as const,
  },
  {
    id: '3',
    actor: { name: 'Priya Sharma', avatar: 'https://i.pravatar.cc/150?img=3' },
    action: 'commented',
    description: 'on "API migration task"',
    timestamp: '2h ago',
    type: 'default' as const,
  },
  {
    id: '4',
    actor: { name: 'Jordan Martinez', avatar: 'https://i.pravatar.cc/150?img=1' },
    action: 'assigned',
    description: '"E2E test suite" to Kai R.',
    timestamp: '5h ago',
    type: 'default' as const,
  },
  {
    id: '5',
    actor: { name: 'Alex Thompson', avatar: 'https://i.pravatar.cc/150?img=5' },
    action: 'created',
    description: '"Deploy staging v2.1.4"',
    timestamp: '5h ago',
    type: 'default' as const,
  },
];

export const Default: Story = {
  args: {
    activities: mockActivities,
  },
};

export const Limited: Story = {
  args: {
    activities: mockActivities,
    maxItems: 3,
  },
};

export const Empty: Story = {
  args: {
    activities: [],
    title: 'Activity Feed',
  },
};

export const CustomTitle: Story = {
  args: {
    activities: mockActivities,
    title: 'Project Timeline',
  },
};
