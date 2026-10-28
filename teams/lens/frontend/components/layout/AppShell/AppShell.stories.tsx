import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import Typography from '@mui/material/Typography';
import { signedInAs } from '@/mocks/session';
import PageContainer from '../PageContainer';
import AppShell from './AppShell';

const meta = {
  component: AppShell,
  tags: ['autodocs'],
  parameters: {
    layout: 'fullscreen',
    // Sidebar reads usePathname() from next/navigation (App Router).
    nextjs: { appDirectory: true, navigation: { pathname: '/manager' } },
  },
  args: {
    children: (
      <PageContainer title="Dashboard">
        <Typography>Page content goes here.</Typography>
      </PageContainer>
    ),
  },
} satisfies Meta<typeof AppShell>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  beforeEach: signedInAs('TEAM_LEAD'),
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('navigation', { name: 'Primary' })).toBeVisible();
    await expect(canvas.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
    await expect(canvas.getByRole('main')).toBeVisible();
  },
};

// Below `md` the permanent sidebar is hidden and the Header's menu button
// opens the drawer instead (rendered in a portal, hence `document.body`).
export const Mobile: Story = {
  beforeEach: signedInAs('TEAM_LEAD'),
  globals: { viewport: { value: 'mobile1', isRotated: false } },
  play: async ({ canvas }) => {
    await userEvent.click(await canvas.findByRole('button', { name: 'Open navigation' }));
    const drawer = await within(document.body).findByRole('navigation', { name: 'Primary' });
    await expect(within(drawer).getByRole('link', { name: 'Dashboard' })).toBeVisible();
  },
};

// Not signed in: the screen isn't shown, the user is sent to /login.
export const SignedOut: Story = {
  play: async ({ canvas }) => {
    await waitFor(() => expect(getRouter().replace).toHaveBeenCalledWith('/login'));
    await expect(canvas.queryByRole('main')).not.toBeInTheDocument();
  },
};

// An employee who types a manager URL is sent to /unauthorized.
export const WrongRole: Story = {
  beforeEach: signedInAs('EMPLOYEE'),
  play: async ({ canvas }) => {
    await waitFor(() => expect(getRouter().replace).toHaveBeenCalledWith('/unauthorized'));
    await expect(canvas.queryByRole('main')).not.toBeInTheDocument();
  },
};
