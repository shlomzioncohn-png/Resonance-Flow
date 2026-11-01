import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fn, expect, within } from 'storybook/test';
import { sampleFlowHealth } from '@/mocks/flowHealthData';
import { bottlenecksGraphHandler } from '@/mocks/handlers';
import FlowFlagDrawer from './FlowFlagDrawer';

const flagOf = (flagId: string) => {
  const flag = sampleFlowHealth.flags.find((candidate) => candidate.flagId === flagId);
  if (!flag) throw new Error(`No sample flag ${flagId}`);
  return flag;
};

const meta = {
  title: 'Pages/Manager/FlowHealth/Drawer',
  component: FlowFlagDrawer,
  tags: ['ai-generated'],
  parameters: { layout: 'fullscreen' },
  args: { onClose: fn() },
  beforeEach: ({ msw }) => {
    msw.use(bottlenecksGraphHandler);
  },
  decorators: [
    // The chronic-blocker view reads the dependency graph (MSW answers it).
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <Story />
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof FlowFlagDrawer>;

export default meta;
type Story = StoryObj<typeof meta>;

const dialog = async () => within(await within(document.body).findByRole('dialog'));

export const ChronicBlocker: Story = {
  args: { flag: flagOf('CHRONIC_BLOCKER:4230f2a0b8') },
  play: async ({ args, userEvent }) => {
    const drawer = await dialog();
    await expect(drawer.getByRole('heading', { name: /Chronic blocker: Finalize auth token service/ })).toBeInTheDocument();
    await expect(drawer.getByText('Worked on: 41 days')).toBeInTheDocument();
    await expect(drawer.getByText('Blocked: 9 days')).toBeInTheDocument();
    // The tasks it blocks come from the dependency graph.
    await expect(await drawer.findByText('Migrate login screen')).toBeInTheDocument();
    await expect(drawer.getByText('דפוסים לאורך זמן, המלצה בלבד')).toBeInTheDocument();
    await userEvent.click(drawer.getByRole('button', { name: 'Close' }));
    await expect(args.onClose).toHaveBeenCalled();
  },
};

export const RecurringCycle: Story = {
  args: { flag: flagOf('RECURRING_CYCLE:81ad03c5e1') },
  play: async () => {
    const drawer = await dialog();
    await expect(
      drawer.getByRole('img', { name: 'Block and release steps: blocked, then released, then blocked, then released, then blocked' }),
    ).toBeInTheDocument();
    await expect(drawer.getByText('Switched 5 times within 36 days.')).toBeInTheDocument();
  },
};

export const RecurringCyclePair: Story = {
  args: { flag: flagOf('RECURRING_CYCLE:5b02e7a9f0') },
  play: async () => {
    const drawer = await dialog();
    await expect(drawer.getByText(/Removed, then returned:/)).toBeInTheDocument();
    await expect(drawer.getByText(/away 11 days/)).toBeInTheDocument();
    await expect(drawer.getByText(/Stayed active meanwhile:/)).toBeInTheDocument();
  },
};

export const SlowChain: Story = {
  args: { flag: flagOf('SLOW_CHAIN:c7e19a44d2') },
  play: async () => {
    const drawer = await dialog();
    await expect(drawer.getByText('Links (3)')).toBeInTheDocument();
    await expect(drawer.getAllByRole('listitem')).toHaveLength(3);
    await expect(drawer.getByText('End to end: 58 days.')).toBeInTheDocument();
  },
};

/** The run's evidence has no per-link durations: the links are listed without invented bars. */
export const SlowChainWithoutLinkDurations: Story = {
  args: {
    flag: { ...flagOf('SLOW_CHAIN:c7e19a44d2'), evidence: { chain_length: 3, end_to_end_days: 58 } },
  },
  play: async () => {
    const drawer = await dialog();
    await expect(drawer.getByText(/no per-link durations/)).toBeInTheDocument();
  },
};

export const Closed: Story = {
  args: { flag: null },
  play: async () => {
    await expect(within(document.body).queryByRole('dialog')).not.toBeInTheDocument();
  },
};
