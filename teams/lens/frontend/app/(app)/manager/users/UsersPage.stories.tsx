import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { http, HttpResponse } from 'msw';
import { expect, waitFor, within } from 'storybook/test';
import { USERS_URL } from '@/mocks/handlers';
import ManagerUsersPage from './page';

// The Gateway here is MSW with the app's own handlers (mocks/handlers.ts, wired
// in .storybook/preview.tsx) - the same 12 users as the app in mock mode.

interface SentRequest {
  method: string;
  url: URL;
  body?: unknown;
}

// Every request the page sent to /api/manager/users, so the play functions can
// assert on what was actually sent - not just on what ended up on screen.
let sent: SentRequest[] = [];

function lastListQuery() {
  const url = sent.filter((request) => request.method === 'GET').at(-1)?.url;
  return {
    path: url?.pathname,
    page: url?.searchParams.get('page'),
    size: url?.searchParams.get('size'),
    sort: url?.searchParams.get('sort'),
  };
}

function createCalls() {
  return sent.filter((request) => request.method === 'POST');
}

const meta = {
  component: ManagerUsersPage,
  tags: ['ai-generated'],
  beforeEach: ({ msw }) => {
    sent = [];
    const record = ({ request }: { request: Request }) => {
      const url = new URL(request.url);
      if (!url.pathname.startsWith('/api/manager/users')) return;
      const entry: SentRequest = { method: request.method, url };
      sent.push(entry);
      if (request.method !== 'GET') {
        request
          .clone()
          .json()
          .then((body) => (entry.body = body));
      }
    };
    msw.events.on('request:start', record);
    return () => {
      msw.events.removeListener('request:start', record);
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

    // 12 users: at 10 per page there's a second page.
    await userEvent.click(canvas.getByRole('combobox', { name: /rows per page/i }));
    await userEvent.click(within(document.body).getByRole('option', { name: '10' }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '0', size: '10', sort: 'displayName,asc' }),
    );

    // findBy: the page shows "Loading…" until the new page arrives.
    await userEvent.click(await canvas.findByRole('button', { name: /next page/i }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '1', size: '10', sort: 'displayName,asc' }),
    );
    // The last two names alphabetically - the page really came from the server.
    await canvas.findByText('Yotam Barak');
    await expect(canvas.queryByText('Dana Cohen')).not.toBeInTheDocument();

    // Changing the sort re-queries from the first page.
    await userEvent.click(await canvas.findByRole('button', { name: 'Name' }));
    await waitFor(() =>
      expect(lastListQuery()).toMatchObject({ page: '0', size: '10', sort: 'displayName,desc' }),
    );
    await expect(await canvas.findByRole('columnheader', { name: 'Name' })).toHaveAttribute(
      'aria-sort',
      'descending',
    );
  },
};

export const OwnStatusCannotBeChanged: Story = {
  play: async ({ canvas }) => {
    await canvas.findByRole('table', { name: 'Users' });

    // Dana Cohen is the acting manager in the mock data (currentUser: true).
    await expect(
      canvas.getByRole('button', { name: 'Change status for Dana Cohen' }),
    ).toBeDisabled();
    await expect(canvas.getByRole('button', { name: 'Edit Dana Cohen' })).toBeEnabled();
    await expect(
      canvas.getByRole('button', { name: 'Change status for Maya Segal' }),
    ).toBeEnabled();
  },
};

export const LoadFailure: Story = {
  // Only the first list request fails; "Try again" then reaches the normal handler.
  beforeEach: ({ msw }) => {
    msw.use(
      http.get(USERS_URL, () => HttpResponse.json({ message: 'Gateway down' }, { status: 503 }), {
        once: true,
      }),
    );
  },
  play: async ({ canvas, userEvent }) => {
    await expect(await canvas.findByRole('alert')).toHaveTextContent(/unavailable/i);
    await expect(canvas.queryByRole('table')).not.toBeInTheDocument();

    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await canvas.findByRole('table', { name: 'Users' });
  },
};

export const CreateUser: Story = {
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('table', { name: 'Users' });
    await userEvent.click(canvas.getByRole('button', { name: 'New user' }));

    const dialog = within(await within(document.body).findByRole('dialog', { name: 'New user' }));
    // Display name is required — Create stays disabled until it's filled in.
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();

    await userEvent.type(dialog.getByLabelText(/display name/i), 'Avital Mor');
    await userEvent.type(dialog.getByLabelText(/^email/i), 'avital@example');
    await expect(dialog.getByText('Enter a valid email address')).toBeInTheDocument();
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.type(dialog.getByLabelText(/^email/i), '.com');

    // The initial password is required, and too short a one keeps Create off.
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.type(dialog.getByLabelText(/initial password/i), 'short');
    await expect(dialog.getByText('At least 8 characters')).toBeInTheDocument();
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.type(dialog.getByLabelText(/initial password/i), '-Pass1');

    await userEvent.click(dialog.getByRole('combobox', { name: /role/i }));
    await userEvent.click(within(document.body).getByRole('option', { name: 'Manager' }));
    await userEvent.click(dialog.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createCalls()).toHaveLength(1));
    const [created] = createCalls();
    await expect(created.url.pathname).toBe('/api/manager/users');
    // Blank optional fields aren't sent at all, there's never a tenant, and the email doubles as the username.
    await waitFor(() =>
      expect(created.body).toEqual({
        displayName: 'Avital Mor',
        email: 'avital@example.com',
        username: 'avital@example.com',
        role: 'MANAGER',
        password: 'short-Pass1',
      }),
    );

    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).not.toBeInTheDocument(),
    );
    // The list is fetched again, and the mock server now has the new user in it.
    await canvas.findByText('Avital Mor');
  },
};

export const CreateUserDuplicateEmail: Story = {
  // No override: the shared handler itself answers 409, because Dana Cohen already has this email.
  play: async ({ canvas, userEvent }) => {
    await canvas.findByRole('table', { name: 'Users' });
    await userEvent.click(canvas.getByRole('button', { name: 'New user' }));

    const dialog = within(await within(document.body).findByRole('dialog', { name: 'New user' }));
    await userEvent.type(dialog.getByLabelText(/display name/i), 'Dana Levi');
    await userEvent.type(dialog.getByLabelText(/^email/i), 'dana.cohen@example.com');
    await userEvent.type(dialog.getByLabelText(/initial password/i), 'Welcome-2026');
    await userEvent.click(dialog.getByRole('button', { name: 'Create' }));

    // Shown on the Email field itself, and Create stays off until the email is changed.
    await expect(await dialog.findByText('A user with this email already exists.')).toBeInTheDocument();
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled();
    // The dialog stays open with what was typed, so the manager can fix it.
    await expect(dialog.getByLabelText(/display name/i)).toHaveValue('Dana Levi');
    await userEvent.type(dialog.getByLabelText(/^email/i), 'x');
    await expect(dialog.getByRole('button', { name: 'Create' })).toBeEnabled();
  },
};
