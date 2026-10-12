import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn } from 'storybook/test';
import ErrorState from './ErrorState';

const meta = {
  component: ErrorState,
  tags: ['ai-generated'],
} satisfies Meta<typeof ErrorState>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {},
};

export const WithRetry: Story = {
  args: {
    onRetry: fn(),
    retryLabel: 'Try again',
  },
  play: async ({ canvas, userEvent, args }) => {
    await userEvent.click(canvas.getByRole('button', { name: /try again/i }));
    await expect(args.onRetry).toHaveBeenCalledOnce();
  },
};
