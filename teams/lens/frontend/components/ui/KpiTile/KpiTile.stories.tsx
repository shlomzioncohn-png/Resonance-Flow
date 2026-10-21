import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import KpiTile from './KpiTile';

const meta = {
  component: KpiTile,
  tags: ['autodocs'],
} satisfies Meta<typeof KpiTile>;

export default meta;
type Story = StoryObj<typeof meta>;

export const TotalProjects: Story = {
  args: { value: 12, label: 'Total projects', tone: 'neutral' },
};

export const Healthy: Story = {
  args: { value: 7, label: 'Healthy', tone: 'healthy' },
};

export const AtRisk: Story = {
  args: { value: 3, label: 'At risk', tone: 'warning' },
};

export const Critical: Story = {
  args: { value: 2, label: 'Critical', tone: 'critical' },
};

/** Up is the good direction here, so the trend is green. */
export const TrendUp: Story = {
  args: {
    value: 48,
    label: 'Completed tasks',
    tone: 'healthy',
    trend: { delta: 8 },
  },
};

/** A drop in a "more is better" number is a bad change, so the trend is red. */
export const TrendDown: Story = {
  args: {
    value: '82%',
    label: 'On-time delivery',
    tone: 'warning',
    trend: { delta: -5 },
  },
};

/** Fewer blocked tasks is good: the arrow points down but the trend is green. */
export const DecreaseIsGood: Story = {
  args: {
    value: 12,
    label: 'Blocked tasks',
    tone: 'critical',
    trend: { delta: -25, goodDirection: 'down' },
  },
};

export const NoChange: Story = {
  args: {
    value: 7,
    label: 'Open projects',
    tone: 'neutral',
    trend: { delta: 0 },
  },
};

export const WithSparkline: Story = {
  args: {
    value: 12,
    label: 'Blocked tasks',
    subtitle: 'Last 7 days',
    tone: 'critical',
    trend: { delta: -25, goodDirection: 'down' },
    sparkline: [16, 15, 17, 14, 15, 13, 12],
    sparklineLabel: 'Blocked tasks over the last 7 days, falling from 16 to 12',
  },
};

/** Without sparklineLabel the line is decorative and hidden from screen readers. */
export const DecorativeSparkline: Story = {
  args: {
    value: 48,
    label: 'Completed tasks',
    subtitle: 'Last 7 days',
    tone: 'healthy',
    trend: { delta: 8 },
    sparkline: [30, 34, 33, 38, 41, 44, 48],
  },
};

export const WithSubtitle: Story = {
  args: {
    value: 7,
    label: 'Healthy',
    subtitle: 'On track this sprint',
    tone: 'healthy',
  },
};

export const LongValue: Story = {
  args: {
    value: '1,284',
    label: 'Tasks processed',
    subtitle: 'This month',
    tone: 'neutral',
    trend: { delta: 12 },
  },
};

export const DurationValue: Story = {
  args: {
    value: '3.2d',
    label: 'Average cycle time',
    subtitle: 'Last 30 days',
    tone: 'warning',
    trend: { delta: 15, goodDirection: 'down' },
    sparkline: [2.4, 2.6, 2.5, 2.9, 3.0, 3.1, 3.2],
    sparklineLabel: 'Average cycle time over the last 30 days, rising from 2.4 to 3.2 days',
  },
};
