import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, screen } from 'storybook/test';
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

export const AllProjects: Story = {
  play: async ({ args, canvas, userEvent }) => {
    // Picking a project replaces the selection - one project at a time.
    await userEvent.click(canvas.getByRole('button', { name: 'Project: All' }));
    // The menu opens in a portal on document.body, outside the canvas.
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Mobile App' }));
    await expect(args.onApplyFilters).toHaveBeenCalledWith({ projectIds: ['p-2'] });
  },
};

export const WithSelectedProject: Story = {
  args: { filters: { period: '30d', projectIds: ['p-1'] } },
  play: async ({ args, canvas, userEvent }) => {
    // Deleting the chip goes back to all projects.
    const chip = canvas.getByRole('button', { name: 'Project: Checkout Revamp' });
    chip.focus();
    await userEvent.keyboard('{Delete}');
    await expect(args.onApplyFilters).toHaveBeenCalledWith({ projectIds: [] });
  },
};

export const WithAskField: Story = {
  args: { showAsk: true, filters: { period: '30d', projectIds: ['p-2'] } },
};

export const UpdatedMinutesAgo: Story = {
  args: { filters: { period: '7d' }, updatedSecondsAgo: 185 },
};
