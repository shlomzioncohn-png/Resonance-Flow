import { Suspense } from 'react';
import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { expect, waitFor, within } from 'storybook/test';
import type { ApiTask, TaskActivity } from '@/services/workspaceApi';
import TaskDetailsPage from './page';

// The whole Activity path - useTaskActivity → getTaskActivity → the Gateway URL → the
// rows - with the Gateway answered by MSW, per story. TaskActivity.stories.tsx covers
// the list's own states (Show more, empty, ...) with props.

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
const TASK_ID = '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c01';
const TASK_URL = `${API_BASE_URL}/api/workspace/tasks/${TASK_ID}`;
const ACTIVITY_URL = `${TASK_URL}/activity`;

const task: ApiTask = {
  id: TASK_ID,
  title: 'Ship the payments page',
  description: 'Hook the new checkout flow up to the payments service.',
  status: 'IN_PROGRESS',
  priority: 'HIGH',
  effortEstimate: 90,
};

const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString();

/** What the BFF returns: already merged, newest first. */
const activity = (overrides: Partial<TaskActivity> = {}): TaskActivity => ({
  items: [
    {
      id: 's2',
      type: 'STATUS_CHANGED',
      createdAt: hoursAgo(2),
      fromStatus: 'PENDING',
      toStatus: 'ACTIVE',
    },
    {
      id: 'd1',
      type: 'DEPENDENCY_ADDED',
      createdAt: hoursAgo(26),
      dependsOnTaskId: '7b1f4c7e-2c1a-4a55-9d0e-5f1f6a3b0c0a',
      dependsOnTitle: 'Finalize auth token service',
      source: 'MIND',
    },
    { id: 's1', type: 'BLOCKED', createdAt: hoursAgo(26), blockingCount: 2 },
  ],
  statusHistoryUnavailable: false,
  dependencyHistoryUnavailable: false,
  ...overrides,
});

/** How many times the page asked for the activity - it should wait for the tab. */
let activityRequests = 0;

const meta = {
  title: 'Pages/Employee/TaskDetails',
  component: TaskDetailsPage,
  tags: ['ai-generated'],
  args: { params: Promise.resolve({ taskId: TASK_ID }) },
  parameters: {
    layout: 'fullscreen',
    nextjs: { appDirectory: true },
  },
  beforeEach: ({ msw }) => {
    activityRequests = 0;
    msw.use(
      http.get(TASK_URL, () => HttpResponse.json(task)),
      http.get(ACTIVITY_URL, () => {
        activityRequests++;
        return HttpResponse.json(activity());
      }),
    );
  },
  decorators: [
    (Story) => (
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        {/* use(params) suspends until the params promise resolves */}
        <Suspense fallback={null}>
          <Story />
        </Suspense>
      </QueryClientProvider>
    ),
  ],
} satisfies Meta<typeof TaskDetailsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ActivityTab: Story = {
  play: async ({ canvas, userEvent }) => {
    await expect(
      await canvas.findByRole('heading', { name: 'Ship the payments page' }),
    ).toBeInTheDocument();
    // Not fetched while the Overview tab is showing.
    await expect(activityRequests).toBe(0);

    await userEvent.click(canvas.getByRole('tab', { name: 'Activity' }));

    const list = within(await canvas.findByRole('list', { name: 'Task activity, newest first' }));
    const rows = list.getAllByRole('listitem');
    await expect(rows).toHaveLength(3);
    await expect(rows[0]).toHaveTextContent('Status: Pending → Active');
    await expect(rows[0]).toHaveTextContent('2 hours ago');
    await expect(rows[1]).toHaveTextContent('Dependency added: Finalize auth token service');
    await expect(rows[1]).toHaveTextContent('by AI');
    await expect(rows[2]).toHaveTextContent('Blocked by 2 tasks');
    await expect(activityRequests).toBe(1);
  },
};

/** Chain's dependency history failed: the status rows still show, with a note. */
export const ActivityPartlyUnavailable: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(ACTIVITY_URL, () =>
        HttpResponse.json(
          activity({
            items: activity().items.filter((item) => !item.type.startsWith('DEPENDENCY_')),
            dependencyHistoryUnavailable: true,
          }),
        ),
      ),
    );
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(await canvas.findByRole('tab', { name: 'Activity' }));

    await expect(await canvas.findByRole('alert')).toHaveTextContent('dependency changes');
    const list = within(canvas.getByRole('list', { name: 'Task activity, newest first' }));
    await expect(list.getAllByRole('listitem')).toHaveLength(2);
  },
};

/** Both of Chain's histories failed: the BFF answers 503 and the tab offers Try again. */
export const ActivityUnavailable: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(ACTIVITY_URL, () => {
        activityRequests++;
        return HttpResponse.json(
          { status: 503, message: 'Task history is unavailable' },
          { status: 503 },
        );
      }),
    );
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(await canvas.findByRole('tab', { name: 'Activity' }));

    await expect(await canvas.findByRole('alert')).toHaveTextContent(
      'Activity could not be loaded',
    );
    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(activityRequests).toBe(2));
  },
};
