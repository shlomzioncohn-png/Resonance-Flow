import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import ProcessStatus from './ProcessStatus';

const meta = {
  title: 'Admin/ProcessStatus',
  component: ProcessStatus,
  tags: ['autodocs'],
} satisfies Meta<typeof ProcessStatus>;

export default meta;
type Story = StoryObj<typeof meta>;

const defaultStages = [
  { name: 'Planning', count: 12 },
  { name: 'In Progress', count: 18 },
  { name: 'Review', count: 8 },
  { name: 'Testing', count: 6 },
  { name: 'Done', count: 20 },
];

export const Default: Story = {
  args: {
    stages: defaultStages,
  },
};

export const CustomTitle: Story = {
  args: {
    title: 'Workflow Stages',
    stages: defaultStages,
  },
};

export const PartialStages: Story = {
  args: {
    stages: [
      { name: 'Backlog', count: 5 },
      { name: 'In Progress', count: 8 },
      { name: 'Done', count: 15 },
    ],
  },
};

/**
 * Demonstrates the color override capability - stages can provide
 * custom color values that override the theme defaults.
 * This story shows accessibility works even with custom colors:
 * - ARIA labels describe each stage and task count
 * - Stage names and numbers provide semantic meaning beyond color
 */
export const WithCustomColors: Story = {
  args: {
    title: 'Custom Color Stages',
    stages: [
      // Dark enough to be text on a light tint (4.5:1)
      { name: 'Backlog', count: 5, color: '#4338ca' },
      { name: 'In Progress', count: 8, color: '#6d28d9' },
      { name: 'Done', count: 15, color: '#0e7490' },
    ],
  },
};
