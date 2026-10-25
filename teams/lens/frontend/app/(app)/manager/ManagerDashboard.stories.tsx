import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { expect, waitFor, within } from 'storybook/test';
import { sampleGraph } from '@/mocks/dependencyGraphData';
import {
  bottlenecksGraphEmptyHandler,
  projectTaskCountsEmptyHandler,
  projectTaskCountsHandler,
} from '@/mocks/handlers';
import ManagerDashboardPage from './page';

// The graph comes from the shared MSW handlers (mocks/handlers.ts).
// The KPIs and the charts are real in the app (BFF counts Inbox's tasks), so they're answered here, per story.

const KPIS_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'}/api/manager/dashboard/kpis`;

const meta = {
  title: 'Pages/Manager/Dashboard',
  component: ManagerDashboardPage,
  tags: ['ai-generated'],
  parameters: {
    layout: 'fullscreen',
    nextjs: { appDirectory: true },
  },
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(KPIS_URL, () =>
        HttpResponse.json({ inProgress: 42, blocked: 9, delayed: 5, avgBlockedDays: null }),
      ),
      projectTaskCountsHandler,
    );
  },
  decorators: [
    (Story) => (
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <Story />
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof ManagerDashboardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvas, userEvent }) => {
    // The KPI row on top.
    const kpis = within(await canvas.findByRole('region', { name: 'Key indicators' }));
    await expect(kpis.getByText('42')).toBeInTheDocument();
    await expect(kpis.getByText('Not available yet')).toBeInTheDocument();

    // The two charts, each with its numbers as a table for screen readers.
    await expect(await canvas.findByRole('heading', { name: 'Tasks by status' })).toBeInTheDocument();
    await expect(canvas.getByRole('heading', { name: 'Tasks per project' })).toBeInTheDocument();
    await expect(canvas.getByRole('table', { name: 'Tasks per project' })).toBeInTheDocument();

    // The five biggest blockers, as a still picture: no zoom controls, no table.
    await expect(await canvas.findAllByRole('group', { name: /Status:/ })).toHaveLength(5);
    await expect(canvas.queryByRole('button', { name: /zoom in/i })).not.toBeInTheDocument();
    const bottlenecks = within(canvas.getByRole('region', { name: 'Bottlenecks' }));
    await expect(bottlenecks.queryByRole('table')).not.toBeInTheDocument();
    await expect(canvas.getByRole('link', { name: 'View all' })).toHaveAttribute(
      'href',
      '/manager/bottlenecks',
    );

    // A task leads to the full screen with that task open.
    await userEvent.click(canvas.getByText('Finalize auth token service', { selector: 'p' }));
    await waitFor(() =>
      expect(getRouter().push).toHaveBeenCalledWith(
        `/manager/bottlenecks?taskId=${sampleGraph.nodes[0].id}`,
      ),
    );
  },
};

export const NoBottlenecks: Story = {
  beforeEach: ({ msw }) => {
    msw.use(bottlenecksGraphEmptyHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('No dependencies')).toBeInTheDocument();
  },
};

/** A tenant with no tasks: the charts say so instead of drawing empty circles. */
export const NoTasks: Story = {
  beforeEach: ({ msw }) => {
    msw.use(projectTaskCountsEmptyHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('No tasks yet')).toBeInTheDocument();
    await expect(canvas.queryByRole('heading', { name: 'Tasks per project' })).not.toBeInTheDocument();
  },
};

/** The KPIs fail on their own: Retry is offered there, and the graph below still shows. */
export const KpisFail: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(KPIS_URL, () =>
        HttpResponse.json(
          {
            status: 'ERROR',
            errorCode: 'SERVICE_UNAVAILABLE',
            message: 'BFF is currently unavailable',
          },
          { status: 503 },
        ),
      ),
    );
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    await expect(await canvas.findAllByRole('group', { name: /Status:/ })).toHaveLength(5);
  },
};
