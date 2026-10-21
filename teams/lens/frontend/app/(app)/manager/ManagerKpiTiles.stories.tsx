import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, within } from 'storybook/test';
import ManagerKpiTiles from './ManagerKpiTiles';

const meta = {
  title: 'Pages/Manager/KpiTiles',
  component: ManagerKpiTiles,
  tags: ['ai-generated'],
  args: {
    kpis: { inProgress: 42, blocked: 9, delayed: 5, avgBlockedDays: null },
  },
} satisfies Meta<typeof ManagerKpiTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

/** What the BFF returns today: avgBlockedDays is null until Chain provides it. */
export const AvgBlockedTimeNotAvailable: Story = {
  play: async ({ canvas }) => {
    const tiles = within(canvas.getByRole('region', { name: 'Key indicators' }));
    await expect(tiles.getByText('42')).toBeInTheDocument();
    await expect(tiles.getByText('In progress')).toBeInTheDocument();
    await expect(tiles.getByText('9')).toBeInTheDocument();
    await expect(tiles.getByText('Blocked')).toBeInTheDocument();
    await expect(tiles.getByText('5')).toBeInTheDocument();
    await expect(tiles.getByText('Delayed')).toBeInTheDocument();

    // The empty state: a dash and an explanation, never "0" or "null".
    await expect(tiles.getByText('Avg. blocked time')).toBeInTheDocument();
    await expect(tiles.getByText('—')).toBeInTheDocument();
    await expect(tiles.getByText('Not available yet')).toBeInTheDocument();
    await expect(tiles.queryByText(/null/)).not.toBeInTheDocument();
  },
};

/** Once Chain provides it: days with one decimal, and no "Not available yet". */
export const AvgBlockedTimeKnown: Story = {
  args: { kpis: { inProgress: 42, blocked: 9, delayed: 5, avgBlockedDays: 3.24 } },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('3.2d')).toBeInTheDocument();
    await expect(canvas.queryByText('Not available yet')).not.toBeInTheDocument();
  },
};

export const AllZero: Story = {
  args: { kpis: { inProgress: 0, blocked: 0, delayed: 0, avgBlockedDays: null } },
  play: async ({ canvas }) => {
    await expect(canvas.getAllByText('0')).toHaveLength(3);
  },
};

export const Loading: Story = {
  args: { kpis: undefined, loading: true },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toHaveTextContent('Loading KPIs…');
    await expect(canvas.queryByText('In progress')).not.toBeInTheDocument();
  },
};

export const Error: Story = {
  args: {
    kpis: undefined,
    error: 'The task service is temporarily unavailable. Please try again in a moment.',
    onRetry: fn(),
  },
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent('KPIs could not be loaded');
    await userEvent.click(canvas.getByRole('button', { name: 'Retry' }));
    await expect(args.onRetry).toHaveBeenCalledOnce();
  },
};
