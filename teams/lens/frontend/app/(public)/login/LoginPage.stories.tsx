import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { getRouter } from '@storybook/nextjs-vite/navigation.mock';
import { http, HttpResponse } from 'msw';
import { expect, waitFor } from 'storybook/test';
import LoginPage from './page';

// POST /auth/login is answered by the shared handler in mocks/handlers.ts
// (manager1@lens.local/manager1, employee1@lens.local/employee1, anything else gets a 401).
// The Email field is type="email", so the browser only submits a real address.

const MANAGER = { email: 'manager1@lens.local', password: 'manager1' };
const EMPLOYEE = { email: 'employee1@lens.local', password: 'employee1' };

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
    await userEvent.type(canvas.getByLabelText(/email/i), MANAGER.email);
    await userEvent.type(canvas.getByLabelText(/password/i), MANAGER.password);
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(getRouter().push).toHaveBeenCalledWith('/manager'));
  },
};

export const EmployeeSignsIn: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), EMPLOYEE.email);
    await userEvent.type(canvas.getByLabelText(/password/i), EMPLOYEE.password);
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(getRouter().push).toHaveBeenCalledWith('/employee'));
  },
};

export const WrongPassword: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), MANAGER.email);
    await userEvent.type(canvas.getByLabelText(/password/i), 'wrong-password');
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));
    await expect(await canvas.findByRole('alert')).toHaveTextContent(
      'Invalid email or password',
    );
    await expect(getRouter().push).not.toHaveBeenCalled();
  },
};

// Overrides the shared handler for this story only: the request fails at the network level.
export const GatewayUnreachable: Story = {
  beforeEach({ msw }) {
    msw.use(http.post('*/auth/login', () => HttpResponse.error()));
  },
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/email/i), MANAGER.email);
    await userEvent.type(canvas.getByLabelText(/password/i), MANAGER.password);
    await userEvent.click(canvas.getByRole('button', { name: 'Sign in' }));
    await expect(await canvas.findByRole('alert')).toHaveTextContent('unreachable');
  },
};
