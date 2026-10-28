import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, within } from 'storybook/test';
import type { Constraint } from '@/services/workspaceApi';
import { OpenConstraintsChip } from '../../shared';
import TaskConstraints from './TaskConstraints';

const constraints: Constraint[] = [
  {
    constraintId: 'c1',
    type: 'APPROVAL_NEEDED',
    status: 'PENDING',
    reason: { approver: 'Legal', note: 'Sign off on the redlines' },
    createdAt: '2026-10-20T09:15:30',
  },
  {
    constraintId: 'c2',
    type: 'BLOCKED_BY_INFO',
    status: 'SATISFIED',
    reason: { note: 'Customer sent the renewal terms' },
    createdAt: '2026-10-21T10:00:00',
  },
  {
    constraintId: 'c3',
    type: 'DATE_WINDOW',
    status: 'WAIVED',
    reason: {},
    createdAt: '2026-10-22T08:30:00',
  },
];

const meta = {
  title: 'Pages/Employee/TaskConstraints',
  component: TaskConstraints,
  tags: ['ai-generated'],
} satisfies Meta<typeof TaskConstraints>;

export default meta;
type Story = StoryObj<typeof meta>;

export const MixedStatuses: Story = {
  args: { constraints },
  play: async ({ canvas }) => {
    // Constraint rows only - not the nested reason lines
    const rows = within(canvas.getByRole('list', { name: 'Task constraints' }))
      .getAllByRole('listitem')
      .filter((el) => el.hasAttribute('data-open'));
    await expect(rows).toHaveLength(3);

    await expect(rows[0]).toHaveTextContent('Approval needed');
    await expect(rows[0]).toHaveTextContent('Pending');
    await expect(rows[0]).toHaveTextContent('approver: Legal');
    await expect(rows[0]).toHaveTextContent('note: Sign off on the redlines');
    await expect(rows[0]).toHaveAttribute('data-open', 'true');

    await expect(rows[1]).toHaveTextContent('Missing info');
    await expect(rows[1]).toHaveTextContent('Satisfied');
    await expect(rows[1]).toHaveAttribute('data-open', 'false');

    // Empty reason -> no reason lines at all
    await expect(rows[2]).toHaveTextContent('Date window');
    await expect(rows[2]).toHaveTextContent('Waived');
    await expect(within(rows[2]).queryAllByRole('listitem')).toHaveLength(0);

    // Read-only: nothing to click
    await expect(canvas.queryAllByRole('button')).toHaveLength(0);
  },
};

export const NoConstraints: Story = {
  args: { constraints: [] },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No constraints on this task')).toBeInTheDocument();
    await expect(canvas.queryByRole('list')).not.toBeInTheDocument();
  },
};

/** Unknown (Chain not consulted) reads the same as none. */
export const ConstraintsMissing: Story = {
  args: { constraints: undefined },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No constraints on this task')).toBeInTheDocument();
  },
};

/** The task list's chip: shows the open count, nothing at 0 or unknown. */
export const OpenCountChip: Story = {
  args: { constraints: [] },
  render: () => (
    <div>
      <div data-testid="one">
        <OpenConstraintsChip count={1} />
      </div>
      <div data-testid="two">
        <OpenConstraintsChip count={2} />
      </div>
      <div data-testid="zero">
        <OpenConstraintsChip count={0} />
      </div>
      <div data-testid="unknown">
        <OpenConstraintsChip />
      </div>
    </div>
  ),
  play: async ({ canvas }) => {
    const one = within(canvas.getByTestId('one')).getByTestId('open-constraints-chip');
    await expect(one).toHaveTextContent('1');
    await expect(one).toHaveAccessibleName('1 open constraint');

    const chip = within(canvas.getByTestId('two')).getByTestId('open-constraints-chip');
    await expect(chip).toHaveTextContent('2');
    await expect(chip).toHaveAccessibleName('2 open constraints');
    await expect(canvas.getByTestId('zero')).toBeEmptyDOMElement();
    await expect(canvas.getByTestId('unknown')).toBeEmptyDOMElement();
  },
};
