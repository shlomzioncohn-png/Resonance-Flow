import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import Button from '@mui/material/Button';
import Typography from '@mui/material/Typography';
import PageContainer from './PageContainer';

const meta = {
  component: PageContainer,
  tags: ['ai-generated'],
} satisfies Meta<typeof PageContainer>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {
    title: 'Manager Dashboard',
    children: <Typography>Page content goes here.</Typography>,
  },
  play: async ({ canvas }) => {
    await expect(
      canvas.getByRole('heading', { level: 1, name: 'Manager Dashboard' }),
    ).toBeVisible();
  },
};

export const WithDescriptionAndActions: Story = {
  args: {
    title: 'Workload',
    description: 'Team workload for the current sprint.',
    actions: (
      <Button variant="contained" size="small">
        Export
      </Button>
    ),
    children: <Typography>Page content goes here.</Typography>,
  },
};
