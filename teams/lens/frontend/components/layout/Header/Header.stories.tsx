import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import { expect, fn } from 'storybook/test'
import Header from './Header'

const meta = {
  component: Header,
  tags: ['ai-generated'],
} satisfies Meta<typeof Header>

export default meta
type Story = StoryObj<typeof meta>

// The mobile menu button (onMenuClick) is only visible below the `md`
// breakpoint (sx={{ display: { md: 'none' } }}), which real per-story
// viewport control would require - there's no viewport addon installed here,
// and Storybook's default preview canvas is wider than `md`, so the button is
// not in the accessibility tree at render time. Assert something that's
// always visible instead: RoleProvider's default (no role set yet) falls
// back to a "Guest user" display rather than crashing.
export const Default: Story = {
  args: { onMenuClick: fn() },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Guest user')).toBeVisible()
  },
}
