import type { Meta, StoryObj } from '@storybook/nextjs-vite';
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
  },
};

export const Employee: Story = {
  beforeEach: signedInAs('EMPLOYEE'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('Eli Employee')).toBeVisible();
    await expect(canvas.getByText('Employee')).toBeVisible();
  },
};
