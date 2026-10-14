import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import EmptyState from '@/components/feedback/EmptyState';
import DataTable, { type DataTableColumn, type DataTableProps } from './DataTable';

interface DemoRow {
  id: string;
  title: string;
  status: string;
  priority: string;
}

const columns: DataTableColumn<DemoRow>[] = [
  { key: 'title', header: 'Title', render: (row) => row.title },
  { key: 'status', header: 'Status', render: (row) => row.status },
  { key: 'priority', header: 'Priority', render: (row) => row.priority, align: 'right' },
];

const rows: DemoRow[] = [
  { id: 'task_001', title: 'Fix login bug', status: 'READY', priority: 'HIGH' },
  { id: 'task_002', title: 'Update documentation', status: 'IN_PROGRESS', priority: 'MEDIUM' },
  { id: 'task_003', title: 'Write onboarding guide', status: 'BLOCKED', priority: 'LOW' },
];

// DataTable<Row> is generic, so `component` is typed against a concrete Row
// (DemoRow) via the Meta<Props> form instead of `typeof DataTable` — writing
// `DataTable<DemoRow>` as a value in a .tsx file would parse as JSX, not a
// generic instantiation.
const meta: Meta<DataTableProps<DemoRow>> = {
  component: DataTable,
  tags: ['autodocs'],
};

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: { columns, rows, getRowId: (row) => row.id },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Fix login bug')).toBeVisible();
  },
};

export const WithRowLinks: Story = {
  args: { columns, rows, getRowId: (row) => row.id, getRowHref: (row) => `/team/${row.id}` },
};

export const Empty: Story = {
  args: {
    columns,
    rows: [],
    getRowId: (row) => row.id,
    emptyState: <EmptyState title="No tasks" description="Nothing matches the current filters." />,
  },
};
