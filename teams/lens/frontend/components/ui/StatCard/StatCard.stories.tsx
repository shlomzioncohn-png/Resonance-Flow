import type { Meta, StoryObj } from '@storybook/react'
import Box from '@mui/material/Box'
import StatCard from './StatCard'

const meta = {
  title: 'UI/StatCard',
  component: StatCard,
  tags: ['autodocs'],
} satisfies Meta<typeof StatCard>

export default meta
type Story = StoryObj<typeof meta>

export const Default: Story = {
  args: {
    label: 'Total Tasks',
    value: 64,
  },
}

export const WithSubtitle: Story = {
  args: {
    label: 'Completion Rate',
    value: '48%',
    subtitle: 'of all tasks',
  },
}

export const Success: Story = {
  args: {
    label: 'Completed',
    value: 20,
    color: 'success',
  },
}

export const Warning: Story = {
  args: {
    label: 'In Progress',
    value: 18,
    color: 'warning',
  },
}

export const Error: Story = {
  args: {
    label: 'Blocked',
    value: 5,
    color: 'error',
  },
}

export const Grid: Story = {
  render: () => (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 2 }}>
      <StatCard label="Total Tasks" value="64" />
      <StatCard label="In Progress" value="18" color="warning" />
      <StatCard label="Blocked" value="5" color="error" />
      <StatCard label="Completed" value="20" color="success" />
      <StatCard label="% Complete" value="48%" color="info" />
    </Box>
  ),
}
