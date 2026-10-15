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

// What POST /api/manager/users answers — a story's beforeEach can switch it to 409.
let createResponse: { status: number; body: unknown } = { status: 201, body: {} };

// Stands in for the Gateway: records every request so the play functions can
// assert on the query the page actually sent.
const fetchMock = fn(async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = new URL(String(input));
  if (init?.method === 'POST') {
    return new Response(JSON.stringify(createResponse.body), { status: createResponse.status });
  }
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
    createResponse = {
      status: 201,
      body: { id: '00000000-0000-0000-0000-000000000009', displayName: 'Noa Levi', role: 'MANAGER', status: 'PENDING', username: '' },
    };
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

function createCalls() {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST');
}

export const CreateUser: Story = {
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('table', { name: 'Users' });
    await userEvent.click(canvas.getByRole('button', { name: 'New user' }));

    const dialog = within(await within(document.body).findByRole('dialog', { name: 'New user' }));
    // Display name is required — Create stays disabled until it's filled in.
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();

    await userEvent.type(dialog.getByLabelText(/display name/i), 'Noa Levi');
    await userEvent.type(dialog.getByLabelText(/^email/i), 'noa@example');
    await expect(dialog.getByText('Enter a valid email address')).toBeInTheDocument();
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.type(dialog.getByLabelText(/^email/i), '.com');

    await userEvent.click(dialog.getByRole('combobox', { name: /role/i }));
    await userEvent.click(within(document.body).getByRole('option', { name: 'Manager' }));
    await userEvent.click(dialog.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createCalls()).toHaveLength(1));
    const [url, init] = createCalls()[0];
    await expect(new URL(String(url)).pathname).toBe('/api/manager/users');
    // Blank optional fields aren't sent at all, and there's never a tenant.
    await expect(JSON.parse(String(init?.body))).toEqual({
      displayName: 'Noa Levi',
      email: 'noa@example.com',
      role: 'MANAGER',
    });

    await waitFor(() => expect(within(document.body).queryByRole('dialog')).not.toBeInTheDocument());
    // The list is fetched again so the new user shows up.
    await waitFor(() => expect(lastListQuery().path).toBe('/api/manager/users'));
  },
};

export const CreateUserDuplicateEmail: Story = {
  beforeEach: () => {
    createResponse = { status: 409, body: { message: 'A user with this email already exists' } };
  },
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('table', { name: 'Users' });
    await userEvent.click(canvas.getByRole('button', { name: 'New user' }));

    const dialog = within(await within(document.body).findByRole('dialog', { name: 'New user' }));
    await userEvent.type(dialog.getByLabelText(/display name/i), 'Dana Levi');
    await userEvent.type(dialog.getByLabelText(/^email/i), 'dana@example.com');
    await userEvent.click(dialog.getByRole('button', { name: 'Create' }));

    await expect(await dialog.findByRole('alert')).toHaveTextContent('A user with this email already exists');
    // The dialog stays open with what was typed, so the manager can fix it.
    await expect(dialog.getByLabelText(/display name/i)).toHaveValue('Dana Levi');
  },
};
