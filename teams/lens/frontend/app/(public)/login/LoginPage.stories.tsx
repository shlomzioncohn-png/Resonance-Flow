import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { http, HttpResponse } from 'msw';
import { expect, waitFor } from 'storybook/test';
import LoginPage from './page';

// POST /api/auth/login is answered by the shared handler in mocks/handlers.ts
// (manager1/manager1, employee1/employee1, anything else gets a 401).
const meta = {
  title: 'Pages/Login',
  component: LoginPage,
  parameters: {
    layout: 'fullscreen',
    // useRouter() comes from next/navigation, so the App Router mock is needed
    nextjs: { appDirectory: true },
  },
} satisfies Meta<typeof LoginPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const ManagerSignsIn: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), 'manager1');
    await userEvent.type(canvas.getByLabelText(/password/i), 'manager1');
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(getRouter().push).toHaveBeenCalledWith('/manager'));
  },
};

export const EmployeeSignsIn: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), 'employee1');
    await userEvent.type(canvas.getByLabelText(/password/i), 'employee1');
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(getRouter().push).toHaveBeenCalledWith('/employee'));
  },
};

export const WrongPassword: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), 'manager1');
    await userEvent.type(canvas.getByLabelText(/password/i), 'wrong-password');
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));

    await expect(await canvas.findByRole('alert')).toHaveTextContent(
      "We couldn't verify who you are",
    );
    await expect(getRouter().push).not.toHaveBeenCalled();
  },
};

// Overrides the shared handler for this story only: the request fails at the network level.
export const GatewayUnreachable: Story = {
  beforeEach({ msw }) {
    msw.use(http.post('*/api/auth/login', () => HttpResponse.error()));
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), 'manager1');
    await userEvent.type(canvas.getByLabelText(/password/i), 'manager1');
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));

    await expect(await canvas.findByRole('alert')).toHaveTextContent('unreachable');
  },
};
