import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { delay, http, HttpResponse } from 'msw';
import { expect, fn, spyOn, waitFor, within } from 'storybook/test';
import { sampleGraph } from '@/mocks/dependencyGraphData';
import type { Constraint } from '@/services/workspaceApi';
import TaskDependenciesDrawer from './TaskDependenciesDrawer';

// The drawer's Constraints block on its own: Chain's constraints (through the
// Gateway) are answered per story, so each one shows a single state.

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
const CONSTRAINTS_URL = `${API_BASE_URL}/api/manager/tasks/:taskId/constraints`;
const CONSTRAINT_URL = `${CONSTRAINTS_URL}/:constraintId`;

const pending: Constraint = {
  constraintId: 'c1',
  type: 'APPROVAL_NEEDED',
  status: 'PENDING',
  reason: { approver: 'Security team', note: 'Token lifetime change' },
  sourceRef: 'mind:run-42',
  createdAt: '2026-10-20T09:15:30',
};
const satisfied: Constraint = { constraintId: 'c2', type: 'DATE_WINDOW', status: 'SATISFIED', reason: {} };
const waived: Constraint = { constraintId: 'c3', type: 'BLOCKED_BY_INFO', status: 'WAIVED', reason: {} };

const listConstraints = (constraints: Constraint[]) =>
  http.get(CONSTRAINTS_URL, () => HttpResponse.json<Constraint[]>(constraints));

/** Answers a status change with the constraint in its new status. */
const changeStatusOk = http.patch(CONSTRAINT_URL, async ({ params, request }) => {
  const { status } = (await request.json()) as { status: Constraint['status'] };
  const constraint = [pending, satisfied, waived].find((c) => c.constraintId === params.constraintId);
  return HttpResponse.json<Constraint>({ ...(constraint ?? pending), status });
});

const gatewayError = (status: number, errorCode: string, message: string) =>
  HttpResponse.json({ status: 'ERROR', errorCode, message, path: '', timestamp: '' }, { status });

// One client per story, kept here so a play function can check what was invalidated.
let queryClient: QueryClient;

const meta = {
  title: 'Pages/Manager/Bottlenecks/TaskDependenciesDrawer',
  component: TaskDependenciesDrawer,
  tags: ['ai-generated'],
  args: {
    task: sampleGraph.nodes[0],
    graph: sampleGraph,
    onSelectTask: fn(),
    onClose: fn(),
  },
  decorators: [
    (Story) => {
      // A fresh cache per story, without retries, so an error shows right away.
      queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      return (
        <QueryClientProvider client={queryClient}>
          <Story />
        </QueryClientProvider>
      );
    },
  ],
  beforeEach: ({ msw }) => {
    msw.use(listConstraints([pending, satisfied, waived]), changeStatusOk);
  },
} satisfies Meta<typeof TaskDependenciesDrawer>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * One element per constraint row. The drawer is a portal, so it's looked up from
 * document.body; only the list's direct children - the reason lines are list items too.
 */
async function constraintRows(): Promise<HTMLElement[]> {
  const list = await within(document.body).findByRole('list', { name: 'Task constraints' });
  return Array.from(list.querySelectorAll<HTMLElement>(':scope > li'));
}

/** A pending constraint offers "Mark satisfied" and "Waive"; satisfied and waived ones offer "Reopen". */
export const ActionsPerStatus: Story = {
  play: async () => {
    const [pendingRow, satisfiedRow, waivedRow] = (await constraintRows()).map((row) => within(row));

    await expect(pendingRow.getByText('Approval needed')).toBeInTheDocument();
    await expect(pendingRow.getByText('Pending')).toBeInTheDocument();
    await expect(pendingRow.getByText('approver: Security team')).toBeInTheDocument();
    await expect(pendingRow.getByText('Source: mind:run-42')).toBeInTheDocument();
    await expect(pendingRow.getByRole('button', { name: 'Mark satisfied' })).toBeEnabled();
    await expect(pendingRow.getByRole('button', { name: 'Waive' })).toBeEnabled();
    await expect(pendingRow.queryByRole('button', { name: 'Reopen' })).not.toBeInTheDocument();

    for (const [row, type, status] of [
      [satisfiedRow, 'Date window', 'Satisfied'],
      [waivedRow, 'Missing info', 'Waived'],
    ] as const) {
      await expect(row.getByText(type)).toBeInTheDocument();
      await expect(row.getByText(status)).toBeInTheDocument();
      await expect(row.getAllByRole('button').map((b) => b.textContent)).toEqual(['Reopen']);
      // No sourceRef -> no "Source:" line
      await expect(row.queryByText(/^Source:/)).not.toBeInTheDocument();
    }
  },
};

/** Marking one satisfied updates its row in place and refreshes every cached graph (for "Blocked"). */
export const MarkSatisfied: Story = {
  play: async ({ userEvent }) => {
    const invalidate = spyOn(queryClient, 'invalidateQueries');
    const row = within((await constraintRows())[0]);
    const body = within(document.body);
    // The lock chip in the header counts the one open constraint...
    await expect(body.getByTestId('open-constraints-chip')).toHaveTextContent('1');

    await userEvent.click(row.getByRole('button', { name: 'Mark satisfied' }));

    await expect(await row.findByText('Satisfied')).toBeInTheDocument();
    // ...and goes away once none is open.
    await expect(body.queryByTestId('open-constraints-chip')).not.toBeInTheDocument();
    await expect(row.queryByText('Pending')).not.toBeInTheDocument();
    await expect(row.getByRole('button', { name: 'Reopen' })).toBeEnabled();
    await expect(row.queryByRole('button', { name: 'Mark satisfied' })).not.toBeInTheDocument();
    await expect(invalidate).toHaveBeenCalledWith({ queryKey: ['manager', 'bottlenecks', 'graph'] });
  },
};

/** Reopening a waived constraint puts it back to Pending. */
export const Reopen: Story = {
  play: async ({ userEvent }) => {
    const row = within((await constraintRows())[2]);

    await userEvent.click(row.getByRole('button', { name: 'Reopen' }));

    await expect(await row.findByText('Pending')).toBeInTheDocument();
    await expect(row.getByRole('button', { name: 'Mark satisfied' })).toBeEnabled();
    await expect(row.getByRole('button', { name: 'Waive' })).toBeEnabled();
  },
};

/** While the change is in flight, that row's buttons are disabled; other rows stay usable. */
export const Saving: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.patch(CONSTRAINT_URL, async () => {
        await delay('infinite');
        return HttpResponse.json({});
      }),
    );
  },
  play: async ({ userEvent }) => {
    const [first, second] = await constraintRows();
    const row = within(first);

    await userEvent.click(row.getByRole('button', { name: 'Waive' }));

    await waitFor(() => expect(row.getByRole('button', { name: 'Waive' })).toBeDisabled());
    await expect(row.getByRole('button', { name: 'Mark satisfied' })).toBeDisabled();
    await expect(row.getByText('Pending')).toBeInTheDocument();
    await expect(within(second).getByRole('button', { name: 'Reopen' })).toBeEnabled();
  },
};

/** A failed change shows an error next to the row; the status doesn't change. */
export const SaveFails: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.patch(CONSTRAINT_URL, () =>
        gatewayError(503, 'SERVICE_UNAVAILABLE', 'Downstream service is unavailable. Please try again later.'),
      ),
    );
  },
  play: async ({ userEvent }) => {
    const row = within((await constraintRows())[0]);

    await userEvent.click(row.getByRole('button', { name: 'Mark satisfied' }));

    await expect(await row.findByRole('alert')).toHaveTextContent(
      'The task service is temporarily unavailable. Please try again in a moment.',
    );
    await expect(row.getByText('Pending')).toBeInTheDocument();
    await expect(row.queryByText('Satisfied')).not.toBeInTheDocument();
    // The buttons come back, so the manager can try again.
    await expect(row.getByRole('button', { name: 'Mark satisfied' })).toBeEnabled();
    await expect(row.getByRole('button', { name: 'Waive' })).toBeEnabled();
  },
};

/** A constraint Chain no longer has (404) gets its own message, not the generic "task" one. */
export const ConstraintGone: Story = {
  beforeEach: ({ msw }) => {
    msw.use(http.patch(CONSTRAINT_URL, () => gatewayError(404, 'NOT_FOUND', 'Constraint not found')));
  },
  play: async ({ userEvent }) => {
    const row = within((await constraintRows())[0]);

    await userEvent.click(row.getByRole('button', { name: 'Waive' }));

    await expect(await row.findByRole('alert')).toHaveTextContent(
      "This constraint wasn't found. It may have been removed.",
    );
    await expect(row.getByText('Pending')).toBeInTheDocument();
  },
};

export const NoConstraints: Story = {
  beforeEach: ({ msw }) => {
    msw.use(listConstraints([]));
  },
  play: async () => {
    const drawer = within(await within(document.body).findByRole('dialog'));
    await expect(await drawer.findByText('No constraints on this task')).toBeInTheDocument();
    await expect(drawer.getByText('Constraints (0)')).toBeInTheDocument();
  },
};

export const Loading: Story = {
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(CONSTRAINTS_URL, async () => {
        await delay('infinite');
        return HttpResponse.json([]);
      }),
    );
  },
  play: async () => {
    const drawer = within(await within(document.body).findByRole('dialog'));
    await expect(await drawer.findByText('Loading constraints…')).toBeInTheDocument();
  },
};

/** Chain failing shows an error in the Constraints block only - the rest of the drawer still works. */
export const LoadFails: Story = {
  beforeEach: ({ msw }) => {
    // The first load fails (Chain down); the retry finds Chain back up.
    let calls = 0;
    msw.use(
      http.get(CONSTRAINTS_URL, () =>
        calls++ === 0
          ? gatewayError(503, 'SERVICE_UNAVAILABLE', 'Downstream service is unavailable. Please try again later.')
          : HttpResponse.json<Constraint[]>([pending]),
      ),
    );
  },
  play: async ({ userEvent }) => {
    const drawer = within(await within(document.body).findByRole('dialog'));
    const constraints = within(drawer.getByRole('region', { name: 'Constraints' }));

    await expect(await constraints.findByRole('alert')).toHaveTextContent(
      'The task service is temporarily unavailable. Please try again in a moment.',
    );
    await expect(drawer.getByText('Blocks (3)')).toBeInTheDocument();

    // Try again loads them once Chain is back.
    await userEvent.click(constraints.getByRole('button', { name: 'Try again' }));
    await expect(await constraints.findByText('Approval needed')).toBeInTheDocument();
  },
};
