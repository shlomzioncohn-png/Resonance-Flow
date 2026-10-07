import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, within } from 'storybook/test';
import DataTable from './DataTable';
import type { Column, DataTableProps } from './types';

interface DemoRow {
  id: string;
  title: string;
  status: string;
  priority: string;
}

const rows: DemoRow[] = [
  { id: 'task_001', title: 'Fix login bug', status: 'READY', priority: 'HIGH' },
  { id: 'task_002', title: 'Update documentation', status: 'IN_PROGRESS', priority: 'MEDIUM' },
  { id: 'task_003', title: 'Write onboarding guide', status: 'BLOCKED', priority: 'LOW' },
];

const renderTitle = fn((row: DemoRow) => row.title);

const columns: Column<DemoRow>[] = [
  { id: 'title', header: 'Title', sortable: true, render: renderTitle },
  { id: 'status', header: 'Status', render: (row) => row.status },
  { id: 'priority', header: 'Priority', render: (row) => row.priority, align: 'right' },
];

// DataTable<T> is generic, so `component` is typed against a concrete row
// (DemoRow) via the Meta<Props> form instead of `typeof DataTable` — writing
// `DataTable<DemoRow>` as a value in a .tsx file would parse as JSX, not a
// generic instantiation.
const meta: Meta<DataTableProps<DemoRow>> = {
  component: DataTable,
  tags: ['ai-generated'],
  args: {
    columns,
    rows,
    getRowId: (row) => row.id,
    ariaLabel: 'Demo tasks',
    onSortChange: fn(),
    onRetry: fn(),
  },
};

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('table', { name: 'Demo tasks' })).toBeVisible();

    const headers = canvas.getAllByRole('columnheader');
    await expect(headers.map((header) => header.textContent)).toEqual([
      'Title',
      'Status',
      'Priority',
    ]);
    for (const header of headers) await expect(header).toHaveAttribute('scope', 'col');

    // One body row per entry in `rows`, each cell produced by its column's render(row).
    const bodyRows = canvas.getAllByRole('row').slice(1);
    await expect(bodyRows).toHaveLength(rows.length);
    await expect(within(bodyRows[1]).getByText('Update documentation')).toBeVisible();
    await expect(within(bodyRows[1]).getByText('IN_PROGRESS')).toBeVisible();
    for (const row of rows) await expect(renderTitle).toHaveBeenCalledWith(row);

    await expect(canvas.queryByRole('navigation')).not.toBeInTheDocument();
    await expect(canvas.queryByText(/rows per page/i)).not.toBeInTheDocument();
  },
};

export const Loading: Story = {
  args: { loading: true },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toBeVisible();
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();
  },
};

export const ErrorWithRetry: Story = {
  args: { error: 'Could not load tasks.' },
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('alert')).toHaveTextContent('Could not load tasks.');
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();

    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await expect(args.onRetry).toHaveBeenCalledTimes(1);
  },
};

export const Empty: Story = {
  args: {
    rows: [],
    emptyTitle: 'No tasks',
    emptyDescription: 'Nothing matches the current filters.',
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('No tasks')).toBeVisible();
    await expect(canvas.getByText('Nothing matches the current filters.')).toBeVisible();
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();
  },
};

export const Unsorted: Story = {
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('columnheader', { name: 'Title' })).not.toHaveAttribute(
      'aria-sort',
    );

    // First click on a sortable column asks for ascending.
    await userEvent.click(canvas.getByRole('button', { name: 'Title' }));
    await expect(args.onSortChange).toHaveBeenLastCalledWith({
      columnId: 'title',
      direction: 'asc',
    });

    // A non-sortable header has no sort control and doesn't react to clicks.
    const statusHeader = canvas.getByRole('columnheader', { name: 'Status' });
    await expect(within(statusHeader).queryByRole('button')).not.toBeInTheDocument();
    await userEvent.click(statusHeader);
    await expect(args.onSortChange).toHaveBeenCalledTimes(1);
  },
};

export const SortedAscending: Story = {
  args: { sort: { columnId: 'title', direction: 'asc' } },
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('columnheader', { name: 'Title' })).toHaveAttribute(
      'aria-sort',
      'ascending',
    );
    await expect(canvas.getByRole('columnheader', { name: 'Status' })).not.toHaveAttribute(
      'aria-sort',
    );

    // Clicking the already-sorted column flips the direction.
    await userEvent.click(canvas.getByRole('button', { name: 'Title' }));
    await expect(args.onSortChange).toHaveBeenLastCalledWith({
      columnId: 'title',
      direction: 'desc',
    });
  },
};

export const SortedDescending: Story = {
  args: { sort: { columnId: 'title', direction: 'desc' } },
  play: async ({ canvas, args, userEvent }) => {
    await expect(canvas.getByRole('columnheader', { name: 'Title' })).toHaveAttribute(
      'aria-sort',
      'descending',
    );

    await userEvent.click(canvas.getByRole('button', { name: 'Title' }));
    await expect(args.onSortChange).toHaveBeenLastCalledWith({
      columnId: 'title',
      direction: 'asc',
    });
  },
};

const onPageChange = fn();
const onPageSizeChange = fn();

export const Paginated: Story = {
  args: {
    pagination: { page: 0, pageSize: 10, totalCount: 42, onPageChange, onPageSizeChange },
  },
  play: async ({ canvas, userEvent }) => {
    await expect(canvas.getByText('1–10 of 42')).toBeVisible();

    await userEvent.click(canvas.getByRole('button', { name: /next page/i }));
    await expect(onPageChange).toHaveBeenLastCalledWith(1);

    await userEvent.click(canvas.getByRole('combobox', { name: /rows per page/i }));
    await userEvent.click(within(document.body).getByRole('option', { name: '50' }));
    await expect(onPageSizeChange).toHaveBeenLastCalledWith(50);
  },
};
