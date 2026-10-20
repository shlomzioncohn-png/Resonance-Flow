import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { expect, waitFor, within } from 'storybook/test';
import { bottlenecksGraphEmptyHandler, bottlenecksGraphErrorHandler } from '@/mocks/handlers';
import ManagerBottlenecksPage from './page';

// GET /api/manager/bottlenecks/graph is answered by the shared handler in
// mocks/handlers.ts (the sample graph); Empty and ServerError swap in the
// alternative handlers exported next to it.

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
    );
  },
} satisfies Meta<typeof ManagerBottlenecksPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvas, userEvent }) => {
    // Clicking the big blocker opens its details.
    await userEvent.click(await canvas.findByText('Finalize auth token service', { selector: 'p' }));
    const drawer = within(await within(document.body).findByRole('dialog'));
    await expect(drawer.getByRole('heading', { name: 'Finalize auth token service' })).toBeInTheDocument();
    await expect(drawer.getByText('Blocks (3)')).toBeInTheDocument();
    await expect(drawer.getByText('Waiting on (0)')).toBeInTheDocument();

    // A task in the drawer moves the drawer to it.
    await userEvent.click(drawer.getByRole('button', { name: /Migrate login screen/ }));
    await expect(drawer.getByRole('heading', { name: 'Migrate login screen' })).toBeInTheDocument();
    await expect(drawer.getByText('Waiting on (1)')).toBeInTheDocument();
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
