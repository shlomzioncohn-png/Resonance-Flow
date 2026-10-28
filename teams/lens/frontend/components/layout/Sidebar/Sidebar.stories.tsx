import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import type { Decorator } from '@storybook/nextjs-vite';
import { RoleProvider, type Role } from '../RoleProvider';
import Sidebar from './Sidebar';

// RoleProvider keeps the role in memory only (set at sign-in), so a story gives
// it a starting role with its own provider, which overrides the one in preview.tsx.
const withRole = (role: Role): Decorator =>
  function WithRole(Story) {
    return (
      <RoleProvider initialRole={role}>
        <Story />
      </RoleProvider>
    );
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
  decorators: [withRole('TENANT_ADMIN')],
  parameters: { nextjs: { navigation: { pathname: '/manager/workload' } } },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('link', { name: 'Workload' })).toBeVisible();
    await expect(canvas.queryByRole('link', { name: 'My Workspace' })).not.toBeInTheDocument();
  },
};

export const EmployeeRole: Story = {
  decorators: [withRole('EMPLOYEE')],
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
