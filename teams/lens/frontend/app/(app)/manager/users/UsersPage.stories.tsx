import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, waitFor, within } from 'storybook/test';
import type { UserListResponse, UserRow } from '@/services/managerUsersApi';
import ManagerUsersPage from './page';

const users: UserRow[] = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    displayName: 'Dana Levi',
    email: 'dana@example.com',
    role: 'MANAGER',
    status: 'ACTIVE',
    lastLoginAt: '2026-10-01T08:30:00Z',
    currentUser: true,
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    displayName: 'Noam Cohen',
    email: 'noam@example.com',
    role: 'EMPLOYEE',
    status: 'SUSPENDED',
  },
];

// Stands in for the Gateway: records every request so the play functions can
// assert on the query the page actually sent.
const fetchMock = fn(async (input: RequestInfo | URL) => {
  const url = new URL(String(input));
  const body: UserListResponse = {
    items: users,
    page: Number(url.searchParams.get('page') ?? 0),
    size: Number(url.searchParams.get('size') ?? 20),
    totalElements: 45,
    totalPages: 3,
  };
  return new Response(JSON.stringify(body), { status: 200 });
});

function lastListQuery() {
  const url = new URL(String(fetchMock.mock.calls.at(-1)?.[0]));
  return {
    path: url.pathname,
    page: url.searchParams.get('page'),
    size: url.searchParams.get('size'),
    sort: url.searchParams.get('sort'),
  };
}

const meta = {
  component: ManagerUsersPage,
  tags: ['ai-generated'],
  beforeEach: () => {
    const realFetch = globalThis.fetch;
    fetchMock.mockClear();
    globalThis.fetch = fetchMock as typeof fetch;
    return () => {
      globalThis.fetch = realFetch;
    };
  },
} satisfies Meta<typeof ManagerUsersPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const ServerSidePagingAndSorting: Story = {
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('table', { name: 'Users' });
    await expect(lastListQuery()).toEqual({
      path: '/api/manager/users',
      page: '0',
      size: '20',
      sort: 'displayName,asc',
    });

    await userEvent.click(canvas.getByRole('button', { name: /next page/i }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '1', size: '20', sort: 'displayName,asc' }),
    );

    // Changing the sort re-queries from the first page.
    await userEvent.click(await canvas.findByRole('button', { name: 'Name' }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '0', size: '20', sort: 'displayName,desc' }),
    );
    await expect(await canvas.findByRole('columnheader', { name: 'Name' })).toHaveAttribute(
      'aria-sort',
      'descending',
    );

    await userEvent.click(canvas.getByRole('combobox', { name: /rows per page/i }));
    await userEvent.click(within(document.body).getByRole('option', { name: '50' }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '0', size: '50', sort: 'displayName,desc' }),
    );
  },
};

export const OwnStatusCannotBeChanged: Story = {
  play: async ({ canvas }) => {
    await canvas.findByRole('table', { name: 'Users' });

    await expect(canvas.getByRole('button', { name: 'Change status for Dana Levi' })).toBeDisabled();
    await expect(canvas.getByRole('button', { name: 'Edit Dana Levi' })).toBeEnabled();
    await expect(
      canvas.getByRole('button', { name: 'Change status for Noam Cohen' }),
    ).toBeEnabled();
  },
};

export const LoadFailure: Story = {
  beforeEach: () => {
    fetchMock.mockImplementationOnce(
      async () => new Response(JSON.stringify({ message: 'Gateway down' }), { status: 503 }),
    );
  },
  play: async ({ canvas, userEvent }) => {
    await expect(await canvas.findByRole('alert')).toHaveTextContent(/unavailable/i);
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();

    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await canvas.findByRole('table', { name: 'Users' });
  },
};
