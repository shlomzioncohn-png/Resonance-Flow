import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect, fn, userEvent, within } from 'storybook/test';
import TaskActions from './TaskActions';

const meta = {
  component: TaskActions,
  args: {
    detailsHref: '#',
    onStart: fn(),
    onDone: fn(),
    onBlock: fn(),
    onReject: fn(),
  },
} satisfies Meta<typeof TaskActions>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Not started yet: Start task is the main action. */
export const NotStarted: Story = {
  args: { status: 'NEW' },
};

/** In progress: the main action becomes Mark as done. */
export const InProgress: Story = {
  args: { status: 'IN_PROGRESS' },
};

/** Saving: everything disabled, spinner on the main button. */
export const Busy: Story = {
  args: { status: 'NEW', busy: true },
};

/** Done / rejected / cancelled: nothing to do but view details. */
export const Closed: Story = {
  args: { status: 'DONE' },
};

/** Opening "Can't do this now" shows the reasons and, last, Reject task. */
export const CantDoMenuOpen: Story = {
  args: { status: 'APPROVED' },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(canvas.getByTestId('cant-do-btn'));
    const body = within(canvasElement.ownerDocument.body);
    await expect(await body.findByText('Waiting for manager approval')).toBeInTheDocument();
    await expect(body.getByText('Reject task…')).toBeInTheDocument();
  },
};

/** Reject needs both a category and a reason before it can be sent. */
export const RejectDialog: Story = {
  args: { status: 'APPROVED' },
  play: async ({ canvasElement, args }) => {
    const canvas = within(canvasElement);
    const body = within(canvasElement.ownerDocument.body);
    await userEvent.click(canvas.getByTestId('cant-do-btn'));
    await userEvent.click(await body.findByText('Reject task…'));
    await expect(await body.findByTestId('reject-confirm')).toBeDisabled();
    await userEvent.type(body.getByLabelText('Tell us more'), 'Belongs to the billing team');
    await expect(body.getByTestId('reject-confirm')).toBeDisabled();
    await expect(args.onReject).not.toHaveBeenCalled();
  },
};
