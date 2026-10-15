import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, userEvent, within } from 'storybook/test';
import Typography from '@mui/material/Typography';
import PageContainer from '../PageContainer';
import AppShell from './AppShell';

const meta = {
  component: AppShell,
  tags: ['autodocs'],
  parameters: {
    layout: 'fullscreen',
    // Sidebar reads usePathname() from next/navigation (App Router).
    nextjs: { appDirectory: true, navigation: { pathname: '/' } },
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
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('navigation', { name: 'Primary' })).toBeVisible();
    await expect(canvas.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible();
    await expect(canvas.getByRole('main')).toBeVisible();
  },
};

// Below `md` the permanent sidebar is hidden and the Header's menu button
// opens the drawer instead (rendered in a portal, hence `document.body`).
export const Mobile: Story = {
  globals: { viewport: { value: 'mobile1', isRotated: false } },
  play: async ({ canvas }) => {
    await userEvent.click(canvas.getByRole('button', { name: 'Open navigation' }));
    const drawer = await within(document.body).findByRole('navigation', { name: 'Primary' });
    await expect(within(drawer).getByRole('link', { name: 'Dashboard' })).toBeVisible();
  },
};
