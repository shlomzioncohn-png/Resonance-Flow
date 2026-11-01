import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { expect, fn } from 'storybook/test';
import { signedInAs } from '@/mocks/session';
import Header from './Header';

const meta = {
  component: Header,
  tags: ['autodocs'],
  parameters: {
    // Sign out calls useRouter() from next/navigation (App Router).
    nextjs: { appDirectory: true },
  },
  args: { onMenuClick: fn() },
} satisfies Meta<typeof Header>;

export default meta;
type Story = StoryObj<typeof meta>;

// The mobile menu button (onMenuClick) is only visible below the `md`
// breakpoint, so the stories assert the signed-in user instead.
export const Default: Story = {
  beforeEach: signedInAs('TEAM_LEAD'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('Maya Manager')).toBeVisible();
    await expect(canvas.getByText('Team Lead')).toBeVisible();
    await expect(canvas.getByRole('button', { name: 'Sign out' })).toBeVisible();
    // No manager-wide search on the server yet, and no notifications at all
    await expect(canvas.queryByRole('search')).not.toBeInTheDocument();
    await expect(canvas.queryByRole('button', { name: /notification/i })).not.toBeInTheDocument();
  },
};

export const Employee: Story = {
  beforeEach: signedInAs('EMPLOYEE'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('Eli Employee')).toBeVisible();
    await expect(canvas.getByText('Employee')).toBeVisible();
  },
};

export const EmployeeSearches: Story = {
  beforeEach: signedInAs('EMPLOYEE'),
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(await canvas.findByRole('textbox', { name: 'Search my tasks' }), 'login bug{Enter}');

    await expect(getRouter().push).toHaveBeenCalledWith('/employee/tasks?search=login%20bug');
  },
};
