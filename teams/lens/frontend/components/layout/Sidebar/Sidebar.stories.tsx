import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import { signedInAs } from '@/mocks/session';
import Sidebar from './Sidebar';

const meta = {
  component: Sidebar,
  tags: ['autodocs'],
  parameters: {
    layout: 'fullscreen',
    // Sidebar reads usePathname() from next/navigation (App Router).
    nextjs: { appDirectory: true, navigation: { pathname: '/manager' } },
  },
  args: {
    variant: 'permanent',
    open: true,
    onClose: fn(),
  },
} satisfies Meta<typeof Sidebar>;

export default meta;
type Story = StoryObj<typeof meta>;

// A manager sees only the Manager section.
export const ManagerRole: Story = {
  beforeEach: signedInAs('TEAM_LEAD'),
  parameters: { nextjs: { navigation: { pathname: '/manager/projects' } } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('link', { name: 'Projects' })).toBeVisible();
    await expect(canvas.queryByRole('link', { name: 'My Workspace' })).not.toBeInTheDocument();
  },
};

// An employee sees only the Employee section.
export const EmployeeRole: Story = {
  beforeEach: signedInAs('EMPLOYEE'),
  parameters: { nextjs: { navigation: { pathname: '/employee' } } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('link', { name: 'My Tasks' })).toBeVisible();
    await expect(canvas.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
  },
};

// Signed out: no links at all.
export const SignedOut: Story = {
  play: async ({ canvas }) => {
    const nav = canvas.getByRole('navigation', { name: 'Primary' });
    await expect(within(nav).queryAllByRole('link')).toHaveLength(0);
  },
};

// The `temporary` variant is the mobile drawer - it is hidden from `md` up, so
// the story pins a mobile viewport. The drawer renders in a portal, outside the
// canvas, hence `within(document.body)`.
export const MobileDrawer: Story = {
  beforeEach: signedInAs('TEAM_LEAD'),
  args: { variant: 'temporary' },
  globals: { viewport: { value: 'mobile1', isRotated: false } },
  play: async ({ args }) => {
    const body = within(document.body);
    await userEvent.click(await body.findByRole('link', { name: 'Projects' }));
    await expect(args.onClose).toHaveBeenCalled();
  },
};
