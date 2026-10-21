import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { fn } from 'storybook/test';
import DashboardHeader from './DashboardHeader';

const projects = [
  { id: 'p-1', name: 'Checkout Revamp' },
  { id: 'p-2', name: 'Mobile App' },
  { id: 'p-3', name: 'Data Platform' },
];

const meta = {
  component: DashboardHeader,
  tags: ['autodocs'],
  args: {
    orgName: 'Acme Corp',
    filters: { period: '30d' },
    onApplyFilters: fn(),
    projects,
    updatedSecondsAgo: 12,
  },
} satisfies Meta<typeof DashboardHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AllProjects: Story = {};

export const WithSelectedProjects: Story = {
  args: { filters: { period: '30d', projectIds: ['p-1', 'p-3'] } },
};

export const WithAskField: Story = {
  args: { showAsk: true, filters: { period: '30d', projectIds: ['p-2'] } },
};

export const UpdatedMinutesAgo: Story = {
  args: { filters: { period: '7d' }, updatedSecondsAgo: 185 },
};
