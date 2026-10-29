import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { expect, waitFor, within } from 'storybook/test';
import { sampleGraph } from '@/mocks/dependencyGraphData';
import {
  bottlenecksGraphEmptyHandler,
  bottlenecksGraphErrorHandler,
  bottlenecksGraphHandler,
} from '@/mocks/handlers';
import ManagerBottlenecksPage from './page';

// GET /api/manager/bottlenecks/graph is real in the app (Chain's graph through
// the BFF), so here it's answered per story: the sample graph by default, and
// Empty and ServerError swap in the alternative handlers from mocks/handlers.ts.

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

const meta = {
  title: 'Pages/Manager/Bottlenecks',
  component: ManagerBottlenecksPage,
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
  beforeEach: ({ msw }) => {
    // The project filter's options (served by the BFF for real).
    msw.use(
      http.get(`${API_BASE_URL}/api/manager/projects`, () =>
        HttpResponse.json([
          { id: 'p3', name: 'Mobile Onboarding' },
          { id: 'p7', name: 'CRM Sync Pipeline' },
        ]),
      ),
      bottlenecksGraphHandler,
    );
  },
} satisfies Meta<typeof ManagerBottlenecksPage>;

export default meta;
type Story = StoryObj<typeof meta>;

const BIG_BLOCKER_ID = sampleGraph.nodes[0].id;
const MIGRATE_LOGIN_ID = sampleGraph.nodes[1].id;

export const Default: Story = {
  play: async ({ canvas, userEvent }) => {
    // Clicking a task puts it in the URL; the drawer opens from there.
    await userEvent.click(await canvas.findByText('Finalize auth token service', { selector: 'p' }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining(`taskId=${BIG_BLOCKER_ID}`), {
        scroll: false,
      }),
    );
  },
};

/** What the Dashboard links to: /manager/bottlenecks?taskId=... opens that task's details. */
export const OpenedFromDashboard: Story = {
  parameters: {
    nextjs: { appDirectory: true, navigation: { query: { taskId: BIG_BLOCKER_ID } } },
  },
  play: async ({ userEvent }) => {
    const drawer = within(await within(document.body).findByRole('dialog'));
    await expect(drawer.getByRole('heading', { name: 'Finalize auth token service' })).toBeInTheDocument();
    await expect(drawer.getByText('Blocks (3)')).toBeInTheDocument();
    await expect(drawer.getByText('Waiting on (0)')).toBeInTheDocument();

    // A task in the drawer moves the drawer to it.
    await userEvent.click(drawer.getByRole('button', { name: /Migrate login screen/ }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining(`taskId=${MIGRATE_LOGIN_ID}`), {
        scroll: false,
      }),
    );
  },
};

export const FilterByProject: Story = {
  play: async ({ canvas, userEvent }) => {
    await canvas.findAllByRole('group', { name: /Status:/ });
    await userEvent.click(canvas.getByText('Project: All projects'));
    await userEvent.click(await within(document.body).findByRole('menuitem', { name: 'CRM Sync Pipeline' }));
    await waitFor(() =>
      expect(getRouter().replace).toHaveBeenCalledWith(expect.stringContaining('projectId=p7'), {
        scroll: false,
      }),
    );
  },
};

export const Empty: Story = {
  beforeEach: ({ msw }) => {
    msw.use(bottlenecksGraphEmptyHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByText('No dependencies')).toBeInTheDocument();
  },
};

export const ServerError: Story = {
  beforeEach: ({ msw }) => {
    msw.use(bottlenecksGraphErrorHandler);
  },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
    await expect(canvas.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  },
};
