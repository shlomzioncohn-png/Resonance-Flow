import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import ProcessStatus from './ProcessStatus';

const meta = {
  title: 'Admin/ProcessStatus',
  component: ProcessStatus,
  tags: ['autodocs'],
  parameters: {
    // TODO(a11y): known color-contrast failures that come from the palette itself
    // (design decision, not a code fix). Only this one rule is off, and only here -
    // every other a11y rule still fails CI. Remove once the colors are revised.
    a11y: { config: { rules: [{ id: 'color-contrast', enabled: false }] } },
  },
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
