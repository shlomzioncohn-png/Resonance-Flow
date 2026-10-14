import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import LoadingState from './LoadingState';

const meta = {
  component: LoadingState,
  tags: ['autodocs'],
} satisfies Meta<typeof LoadingState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {},
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toHaveTextContent('Loading…');
  },
};

export const CustomMessage: Story = {
  args: { message: 'Fetching your workload…' },
};
