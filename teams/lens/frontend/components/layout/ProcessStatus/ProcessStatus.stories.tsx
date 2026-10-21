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
      { name: 'Backlog', count: 5, color: '#6366f1' },
      { name: 'In Progress', count: 8, color: '#8b5cf6' },
      { name: 'Done', count: 15, color: '#06b6d4' },
    ],
  },
};
