import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn } from 'storybook/test';
import ChangePasswordForm from './ChangePasswordForm';

const meta = {
  component: ChangePasswordForm,
  tags: ['autodocs'],
  parameters: { layout: 'fullscreen' },
  args: { displayName: 'Dana Cohen', onSubmit: fn() },
} satisfies Meta<typeof ChangePasswordForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithoutName: Story = {
  args: { displayName: undefined },
};

export const SavesNewPassword: Story = {
  play: async ({ args, canvas, userEvent }) => {
    const save = canvas.getByRole('button', { name: 'Save password' });
    await expect(save).toBeDisabled();

    await userEvent.type(canvas.getByLabelText(/^new password/i), 'my-own-pass');
    await userEvent.type(canvas.getByLabelText(/confirm new password/i), 'my-own-pass');
    await expect(save).toBeEnabled();
    await userEvent.click(save);

    await expect(args.onSubmit).toHaveBeenCalledWith('my-own-pass');
  },
};

export const TooShort: Story = {
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/^new password/i), 'short');
    await userEvent.type(canvas.getByLabelText(/confirm new password/i), 'short');

    await expect(canvas.getByText('At least 8 characters')).toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: 'Save password' })).toBeDisabled();
    await expect(args.onSubmit).not.toHaveBeenCalled();
  },
};

export const PasswordsDontMatch: Story = {
  play: async ({ args, canvas, userEvent }) => {
    await userEvent.type(canvas.getByLabelText(/^new password/i), 'my-own-pass');
    await userEvent.type(canvas.getByLabelText(/confirm new password/i), 'my-own-pas');

    await expect(canvas.getByText("Passwords don't match")).toBeInTheDocument();
    await expect(canvas.getByRole('button', { name: 'Save password' })).toBeDisabled();
    await expect(args.onSubmit).not.toHaveBeenCalled();
  },
};

export const Saving: Story = {
  args: { submitting: true },
};

export const ServerError: Story = {
  args: { error: "Couldn't save the password. Please try again." },
};
