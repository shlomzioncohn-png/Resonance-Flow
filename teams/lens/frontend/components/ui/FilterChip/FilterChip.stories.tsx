import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import { fn } from 'storybook/test'
import FilterChip from './FilterChip'

const meta = {
  component: FilterChip,
  tags: ['ai-generated'],
} satisfies Meta<typeof FilterChip>

export default meta
type Story = StoryObj<typeof meta>

export const Status: Story = {
  args: {
    label: 'Status',
    value: 'all',
    options: [
      { label: 'All', value: 'all' },
      { label: 'Healthy', value: 'healthy' },
      { label: 'At risk', value: 'warning' },
      { label: 'Critical', value: 'critical' },
    ],
    onChange: fn(),
  },
}

export const Team: Story = {
  args: {
    label: 'Team',
    value: 'all',
    options: [
      { label: 'All', value: 'all' },
      { label: 'Platform Team', value: 'platform' },
      { label: 'Growth Team', value: 'growth' },
      { label: 'Data Team', value: 'data' },
    ],
    onChange: fn(),
  },
}

export const SortByHealth: Story = {
  args: {
    label: 'Sort',
    value: 'health',
    options: [
      { label: 'Health', value: 'health' },
      { label: 'Name', value: 'name' },
      { label: 'Last updated', value: 'updated' },
    ],
    onChange: fn(),
  },
}
