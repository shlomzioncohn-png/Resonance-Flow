import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import Sidebar from './Sidebar';

// RoleProvider (mounted in .storybook/preview.tsx) hydrates the role from
// localStorage on mount - seed it before the story renders and clear it after,
// so a role never leaks into the next story.
const ROLE_STORAGE_KEY = 'lens.role';
const withRole = (role: 'manager' | 'employee') => () => {
  window.localStorage.setItem(ROLE_STORAGE_KEY, role);
  return () => window.localStorage.removeItem(ROLE_STORAGE_KEY);
};

const meta = {
  component: Sidebar,
  tags: ['autodocs'],
  parameters: {
    layout: 'fullscreen',
    // Sidebar reads usePathname() from next/navigation (App Router).
    nextjs: { appDirectory: true, navigation: { pathname: '/' } },
  },
  args: {
    variant: 'permanent',
    open: true,
    onClose: fn(),
  },
} satisfies Meta<typeof Sidebar>;

export default meta;
type Story = StoryObj<typeof meta>;

// No role set yet: every section is listed.
export const Default: Story = {
  play: async ({ canvas }) => {
    const nav = canvas.getByRole('navigation', { name: 'Primary' });
    await expect(within(nav).getByRole('link', { name: 'Dashboard' })).toBeVisible();
    await expect(within(nav).getByRole('link', { name: 'Manager Dashboard' })).toBeVisible();
    await expect(within(nav).getByRole('link', { name: 'My Workspace' })).toBeVisible();
  },
};

export const ManagerRole: Story = {
  beforeEach: withRole('manager'),
  parameters: { nextjs: { navigation: { pathname: '/manager/workload' } } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('link', { name: 'Workload' })).toBeVisible();
    await expect(canvas.queryByRole('link', { name: 'My Workspace' })).not.toBeInTheDocument();
  },
};

export const EmployeeRole: Story = {
  beforeEach: withRole('employee'),
  parameters: { nextjs: { navigation: { pathname: '/employee' } } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('link', { name: 'My Tasks' })).toBeVisible();
    await expect(canvas.queryByRole('link', { name: 'Dashboard' })).not.toBeInTheDocument();
  },
};

// The `temporary` variant is the mobile drawer - it is hidden from `md` up, so
// the story pins a mobile viewport. The drawer renders in a portal, outside the
// canvas, hence `within(document.body)`.
export const MobileDrawer: Story = {
  args: { variant: 'temporary' },
  globals: { viewport: { value: 'mobile1', isRotated: false } },
  play: async ({ args }) => {
    const body = within(document.body);
    await userEvent.click(await body.findByRole('link', { name: 'Workload' }));
    await expect(args.onClose).toHaveBeenCalled();
  },
};
