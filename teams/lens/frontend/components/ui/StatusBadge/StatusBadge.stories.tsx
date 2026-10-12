import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { expect } from 'storybook/test';
import StatusBadge from './StatusBadge';

const meta = {
  component: StatusBadge,
  tags: ['autodocs'],
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = {
  args: { status: 'healthy', label: 'Healthy' },
};

export const Warning: Story = {
  args: { status: 'warning', label: 'At risk' },
};

export const Critical: Story = {
  args: { status: 'critical', label: 'Critical' },
};

export const Neutral: Story = {
  args: { status: 'neutral', label: 'Unknown' },
};

// The one CssCheck for the whole project: proves the shared preview actually
// loaded the real LENS theme. StatusBadge reads its colors from
// theme.palette.status (an app-specific palette group added in
// theme/palette.ts), not from MUI's defaults, so a correct background here
// means the real `theme/` module rendered end to end.
export const CssCheck: Story = {
  args: { status: 'healthy', label: 'Healthy' },
  play: async ({ canvas }) => {
    const chip = canvas.getByText('Healthy').closest('.MuiChip-root');
    // statusTokens.healthy.bg = colorTokens.green[50] = #f0fdf4
    await expect(getComputedStyle(chip as Element).backgroundColor).toBe('rgb(240, 253, 244)');
  },
};
