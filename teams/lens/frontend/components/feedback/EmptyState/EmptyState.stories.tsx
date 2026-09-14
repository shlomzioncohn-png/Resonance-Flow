import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import { expect } from 'storybook/test'
import Button from '@mui/material/Button'
import EmptyState from './EmptyState'

const meta = {
  component: EmptyState,
  tags: ['ai-generated'],
} satisfies Meta<typeof EmptyState>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {},
}

export const WithDescriptionAndAction: Story = {
  args: {
    title: 'No blocked tasks',
    description: 'Nothing is currently blocked for your team.',
    action: (
      <Button variant="outlined" size="small">
        Refresh
      </Button>
    ),
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No blocked tasks')).toBeVisible()
    await expect(canvas.getByRole('button', { name: /refresh/i })).toBeVisible()
  },
}
