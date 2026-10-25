import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { expect, screen, waitFor, within } from 'storybook/test';
import { sampleGraph } from '@/mocks/dependencyGraphData';
import { bottlenecksGraphEmptyHandler } from '@/mocks/handlers';
import ManagerDashboardPage from './page';

// The graph comes from the shared MSW handler (mocks/handlers.ts): the same sample data as /manager/bottlenecks.
// The KPIs are real in the app (BFF counts Inbox's tasks), so they're answered here, per story.

const KPIS_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'}/api/manager/dashboard/kpis`;

/** The query string of the last KPI request, recorded by FiltersFromUrl. */
let kpiRequestQuery: string | null = null;

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

    // The five biggest blockers, as a still picture: no zoom controls, no table.
    await expect(await canvas.findAllByRole('group', { name: /Status:/ })).toHaveLength(5);
    await expect(canvas.queryByRole('button', { name: /zoom in/i })).not.toBeInTheDocument();
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();
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

export const ChangePeriodFilter: Story = {
  play: async ({ canvas, userEvent }) => {
    // A chip change goes through applyFilters, which writes the filters to the URL.
    await userEvent.click(canvas.getByRole('button', { name: /Period: Last 30 days/ }));
    // The menu opens in a portal on document.body, outside the canvas.
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Last 7 days' }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining('?period=7d'), {
        scroll: false,
      }),
    );
  },
};

/** The filters in the URL reach the KPI request, so a filter change refetches the tiles. */
export const FiltersFromUrl: Story = {
  parameters: {
    nextjs: { navigation: { query: { period: '7d', project: 'p-1' } } },
  },
  beforeEach: ({ msw }) => {
    kpiRequestQuery = null;
    msw.use(
      http.get(KPIS_URL, ({ request }) => {
        kpiRequestQuery = new URL(request.url).search;
        return HttpResponse.json({ inProgress: 3, blocked: 1, delayed: 0, avgBlockedDays: null });
      }),
    );
  },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('button', { name: /Period: Last 7 days/ }),
    ).toBeInTheDocument();
    await waitFor(() => expect(kpiRequestQuery).toBe('?period=7d&project=p-1'));
    // The full graph opens on the same project.
    await expect(canvas.getByRole('link', { name: 'View all' })).toHaveAttribute(
      'href',
      '/manager/bottlenecks?projectId=p-1',
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
