import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, within } from 'storybook/test';
import type { TaskActivityItem } from '@/services/workspaceApi';
import TaskActivity from './TaskActivity';

const NOW = new Date('2026-10-20T12:00:00Z');
const hoursAgo = (hours: number) => new Date(NOW.getTime() - hours * 3_600_000).toISOString();

const recent: TaskActivityItem[] = [
  {
    id: 's3',
    type: 'STATUS_CHANGED',
    createdAt: hoursAgo(2),
    fromStatus: 'PENDING',
    toStatus: 'ACTIVE',
  },
  { id: 's2', type: 'UNBLOCKED', createdAt: hoursAgo(5), blockingCount: 0 },
  {
    id: 'd2',
    type: 'DEPENDENCY_REMOVED',
    createdAt: hoursAgo(5),
    dependsOnTaskId: 'b2',
    dependsOnTitle: 'Migrate login screen',
    source: 'SYSTEM',
  },
  { id: 's1', type: 'BLOCKED', createdAt: hoursAgo(26), blockingCount: 2 },
  {
    id: 'd1',
    type: 'DEPENDENCY_ADDED',
    createdAt: hoursAgo(30),
    dependsOnTaskId: 'b1',
    dependsOnTitle: 'Finalize auth token service',
    source: 'MIND',
  },
];

/** 45 rows: three "pages" of 20 for Show more. */
const long: TaskActivityItem[] = Array.from({ length: 45 }, (_, i) => ({
  id: `e${i}`,
  type: 'DEPENDENCY_ADDED' as const,
  createdAt: hoursAgo(i + 1),
  dependsOnTaskId: `t${i}`,
  dependsOnTitle: `Task ${i + 1}`,
  source: 'USER',
}));

const meta = {
  title: 'Pages/Employee/TaskActivity',
  component: TaskActivity,
  tags: ['ai-generated'],
  args: {
    now: NOW,
    activity: {
      items: recent,
      statusHistoryUnavailable: false,
      dependencyHistoryUnavailable: false,
    },
  },
} satisfies Meta<typeof TaskActivity>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvas }) => {
    const rows = within(canvas.getByRole('list', { name: /task activity/i })).getAllByRole(
      'listitem',
    );
    await expect(rows.map((row) => row.textContent)).toEqual([
      'Status: Pending → Active2 hours ago',
      'Unblocked5 hours ago',
      'Dependency removed: Migrate login screen5 hours ago · by system',
      'Blocked by 2 tasks1 day ago',
      'Dependency added: Finalize auth token service1 day ago · by AI',
    ]);
    // The exact time is one hover/focus away.
    await expect(canvas.getAllByText('2 hours ago')[0].closest('time')).toHaveAttribute(
      'datetime',
      hoursAgo(2),
    );
    await expect(canvas.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument();
  },
};

export const ShowMore: Story = {
  args: {
    activity: { items: long, statusHistoryUnavailable: false, dependencyHistoryUnavailable: false },
  },
  play: async ({ canvas, userEvent }) => {
    const list = () => within(canvas.getByRole('list', { name: /task activity/i }));
    await expect(list().getAllByRole('listitem')).toHaveLength(20);
    await expect(canvas.getByText('Showing 20 of 45')).toBeInTheDocument();

    await userEvent.click(canvas.getByRole('button', { name: 'Show more' }));
    await expect(list().getAllByRole('listitem')).toHaveLength(40);

    await userEvent.click(canvas.getByRole('button', { name: 'Show more' }));
    await expect(list().getAllByRole('listitem')).toHaveLength(45);
    await expect(canvas.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument();
  },
};

/** Chain's dependency history failed: the status history still shows, with a note. */
export const PartlyUnavailable: Story = {
  args: {
    activity: {
      items: recent.filter((item) => !item.type.startsWith('DEPENDENCY_')),
      statusHistoryUnavailable: false,
      dependencyHistoryUnavailable: true,
    },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent('dependency changes');
    await expect(canvas.getByText('Status: Pending → Active')).toBeInTheDocument();
  },
};

export const NoActivityYet: Story = {
  args: {
    activity: { items: [], statusHistoryUnavailable: false, dependencyHistoryUnavailable: false },
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No activity yet')).toBeInTheDocument();
  },
};

export const Loading: Story = {
  args: { activity: undefined, loading: true },
};

export const Error: Story = {
  args: {
    activity: undefined,
    error: 'The task service is temporarily unavailable. Please try again in a moment.',
    onRetry: fn(),
  },
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent('Activity could not be loaded');
    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await expect(args.onRetry).toHaveBeenCalledOnce();
  },
};
