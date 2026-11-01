import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, waitFor, within } from 'storybook/test';
import {
  buildLargeGraph,
  emptyGraph,
  longTitlesGraph,
  sampleGraph,
  singleBlockerGraph,
} from '@/mocks/dependencyGraphData';
import DependencyGraph from './DependencyGraph';

// The same sample data the MSW handler serves (mocks/dependencyGraphData.ts),
// so these stories and the app in mock mode always show the same graph.

const meta = {
  title: 'UI/DependencyGraph',
  component: DependencyGraph,
  tags: ['ai-generated'],
  args: {
    graph: sampleGraph,
    onNodeSelect: fn(),
  },
} satisfies Meta<typeof DependencyGraph>;

export default meta;
type Story = StoryObj<typeof meta>;

const BIG_BLOCKER = /^Finalize auth token service\./;

export const Default: Story = {
  play: async ({ canvas, args, userEvent }) => {
    // The big blocker says so in its accessible name, not only by color.
    const blocker = await canvas.findByRole('group', { name: BIG_BLOCKER });
    await expect(blocker).toHaveAccessibleName(
      'Finalize auth token service. Status: Active. Blocks 12 tasks. Critical blocker.',
    );

    // Keyboard: focus a task, Enter selects it, an arrow moves to the next one.
    blocker.focus();
    await userEvent.keyboard('{Enter}');
    await expect(args.onNodeSelect).toHaveBeenCalledWith(sampleGraph.nodes[0].id);
    await userEvent.keyboard('{ArrowRight}');
    await waitFor(() => expect(document.activeElement).not.toBe(blocker));
    await expect(document.activeElement?.getAttribute('aria-roledescription')).toBe('node');

    // Clicking works too.
    await userEvent.click(canvas.getByText('Session refresh API', { selector: 'p' }));
    await expect(args.onNodeSelect).toHaveBeenLastCalledWith(
      sampleGraph.nodes.find((node) => node.title === 'Session refresh API')?.id,
    );

    // The table lists the same tasks, most blocking first.
    const table = canvas.getByRole('table', { name: 'Tasks by number of tasks blocked' });
    const firstRow = within(table).getAllByRole('row')[1];
    await expect(firstRow).toHaveTextContent('Finalize auth token service');
    await expect(firstRow).toHaveTextContent('12 (critical)');
    await expect(canvas.getByRole('button', { name: /zoom in/i })).toBeInTheDocument();
  },
};

/** A task with open constraints shows the lock chip with their count, in the graph and in the table. */
export const OpenConstraints: Story = {
  play: async ({ canvas }) => {
    const node = await canvas.findByRole('group', { name: /^Migrate login screen\./ });
    await expect(node).toHaveAccessibleName(
      'Migrate login screen. Status: Pending. Blocked. 2 open constraints. Blocks 4 tasks.',
    );
    await expect(within(node).getByTestId('open-constraints-chip')).toHaveTextContent('2');

    const table = canvas.getByRole('table', { name: 'Tasks by number of tasks blocked' });
    const row = within(table).getByRole('row', { name: /Migrate login screen/ });
    await expect(within(row).getByLabelText('2 open constraints')).toHaveTextContent('2');
    // A task without any shows no chip.
    const blockerRow = within(table).getByRole('row', { name: /Finalize auth token service/ });
    await expect(within(blockerRow).queryByTestId('open-constraints-chip')).not.toBeInTheDocument();
  },
};

export const EmptyGraph: Story = {
  args: { graph: emptyGraph },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No dependencies')).toBeInTheDocument();
  },
};

export const SingleBlocker: Story = {
  args: { graph: singleBlockerGraph, criticalThreshold: 3 },
  play: async ({ canvas }) => {
    await expect(await canvas.findByRole('group', { name: BIG_BLOCKER })).toHaveAccessibleName(
      /Blocks 3 tasks\. Critical blocker\.$/,
    );
  },
};

const largeGraph = buildLargeGraph();

/** 200 tasks: React Flow only renders what's on screen, so zoom and pan stay smooth. */
export const LargeGraph: Story = {
  args: { graph: largeGraph },
  play: async ({ canvas }) => {
    await canvas.findAllByRole('group', { name: /^Task \d+\./ });
    await expect(canvas.getByText('1–10 of 200')).toBeInTheDocument();
  },
};

/** Titles are cut to two lines in the node; the full title is still in its accessible name. */
export const LongTitles: Story = {
  args: { graph: longTitlesGraph },
  play: async ({ canvas }) => {
    await expect(
      await canvas.findByRole('group', { name: /^Replace the legacy authentication token service/ }),
    ).toBeInTheDocument();
  },
};

export const Loading: Story = {
  args: { graph: undefined, loading: true },
};

export const Error: Story = {
  args: {
    graph: undefined,
    error: 'The task service is temporarily unavailable. Please try again in a moment.',
    onRetry: fn(),
  },
  play: async ({ canvas, args, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await expect(args.onRetry).toHaveBeenCalled();
  },
};

/** The Dashboard's small view: the five biggest blockers, fixed height, no zoom controls, no table. */
export const Compact: Story = {
  args: { variant: 'compact', interactive: false },
  play: async ({ canvas, args, userEvent }) => {
    const nodes = await canvas.findAllByRole('group', { name: /Status:/ });
    await expect(nodes).toHaveLength(5);
    await expect(canvas.queryByRole('button', { name: /zoom in/i })).not.toBeInTheDocument();
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();

    // Clicking still reports the task; the page decides where that leads.
    await userEvent.click(canvas.getByText('Finalize auth token service', { selector: 'p' }));
    await expect(args.onNodeSelect).toHaveBeenCalledWith(sampleGraph.nodes[0].id);
  },
};

export const CompactEmpty: Story = {
  args: { variant: 'compact', interactive: false, graph: emptyGraph },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No dependencies')).toBeInTheDocument();
  },
};
