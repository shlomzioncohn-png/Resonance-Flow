import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { expect, waitFor, within } from 'storybook/test';
import { flowHealthEmptyHandler, flowHealthUnavailableHandler } from '@/mocks/handlers';
import { sampleFlowHealth } from '@/mocks/flowHealthData';
import ManagerFlowHealthPage from './page';

// GET /api/manager/flow-health is answered by the shared handler in mocks/handlers.ts
// (the sample flags); Empty and Unavailable swap in the alternatives exported next to it.

const FIRST_FLAG_ID = sampleFlowHealth.flags[0].flagId;

const meta = {
  title: 'Pages/Manager/FlowHealth',
  component: ManagerFlowHealthPage,
  tags: ['ai-generated'],
  parameters: {
    layout: 'fullscreen',
    // useRouter()/useSearchParams() come from next/navigation, so the App Router mock is needed
    nextjs: { appDirectory: true },
  },
  decorators: [
    // A fresh cache per story, without retries, so an error shows right away.
    (Story) => (
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <Story />
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof ManagerFlowHealthPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvas }) => {
    // The fixed caption and the read-only wording are always there; nothing on the page writes.
    await expect(canvas.getByText('דפוסים לאורך זמן, המלצה בלבד')).toBeInTheDocument();
    await expect(await canvas.findByText('Chronic blockers')).toBeInTheDocument();
    await expect(canvas.getByText('Blocker cycles')).toBeInTheDocument();
    await expect(canvas.getByText('Slow chains')).toBeInTheDocument();
    await expect(canvas.getByText(/Last run: 28 Oct 2026/)).toBeInTheDocument();

    const table = canvas.getByRole('table', { name: 'Flow-health flags' });
    await expect(within(table).getAllByRole('row')).toHaveLength(sampleFlowHealth.flags.length + 1);
    await expect(within(table).getByText('Finalize auth token service')).toBeInTheDocument();
    await expect(canvas.queryByRole('button', { name: /fix|resolve|apply/i })).not.toBeInTheDocument();
  },
};

export const OpenFlag: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(await canvas.findByRole('button', { name: /Open details: Finalize auth token service/ }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining(`flagId=${FIRST_FLAG_ID}`), {
        scroll: false,
      }),
    );
  },
};

export const FilterBySeverity: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(await canvas.findByText('Severity: All severities'));
    await userEvent.click(await within(document.body).findByRole('menuitem', { name: 'High' }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining('severity=high'), { scroll: false }),
    );
  },
};

export const FilteredToNothing: Story = {
  parameters: {
    nextjs: { appDirectory: true, navigation: { query: { pattern: 'SLOW_CHAIN', severity: 'low' } } },
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('No flags match these filters')).toBeInTheDocument();
  },
};

export const NotEnoughHistory: Story = {
  beforeEach: ({ msw }) => {
    msw.use(flowHealthEmptyHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('אין עדיין מספיק היסטוריה')).toBeInTheDocument();
    await expect(canvas.getByText('דפוסים לאורך זמן, המלצה בלבד')).toBeInTheDocument();
  },
};

export const ChainUnavailable: Story = {
  beforeEach: ({ msw }) => {
    msw.use(flowHealthUnavailableHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('alert')).toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  },
};
