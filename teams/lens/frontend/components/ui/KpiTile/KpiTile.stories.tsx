import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import KpiTile from './KpiTile'

const meta = {
  component: KpiTile,
  tags: ['ai-generated'],
} satisfies Meta<typeof KpiTile>

export default meta
type Story = StoryObj<typeof meta>

export const TotalProjects: Story = {
  args: { value: 12, label: 'Total projects', tone: 'neutral' },
}

export const Healthy: Story = {
  args: { value: 7, label: 'Healthy', tone: 'healthy' },
}

export const AtRisk: Story = {
  args: { value: 3, label: 'At risk', tone: 'warning' },
}

export const Critical: Story = {
  args: { value: 2, label: 'Critical', tone: 'critical' },
}
