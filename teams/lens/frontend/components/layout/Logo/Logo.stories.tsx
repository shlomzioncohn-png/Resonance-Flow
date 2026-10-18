import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import Box from '@mui/material/Box';
import Logo from './Logo';

const meta = {
  component: Logo,
  tags: ['autodocs'],
} satisfies Meta<typeof Logo>;

export default meta;
type Story = StoryObj<typeof meta>;

// The `dark` variant renders white text, meant for the Sidebar's dark surface -
// on the default white canvas it would be invisible, so give it that surface.
export const Dark: Story = {
  args: { variant: 'dark' },
  decorators: [
    (Story) => (
      <Box sx={{ bgcolor: 'sidebar.background', p: 2, display: 'inline-block' }}>
        <Story />
      </Box>
    ),
  ],
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Resonance Flow')).toBeVisible();
    await expect(canvas.getByText('LENS')).toBeVisible();
  },
};

export const Light: Story = {
  args: { variant: 'light' },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Resonance Flow')).toBeVisible();
  },
};
