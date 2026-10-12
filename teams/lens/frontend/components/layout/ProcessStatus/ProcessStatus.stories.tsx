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
