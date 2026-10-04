import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import ProjectCard from './ProjectCard';

const meta = {
  component: ProjectCard,
  tags: ['ai-generated'],
  // ProjectCard is a fixed-width grid tile (358px in the Figma frame), not a
  // full-bleed component - constrain the canvas so the card renders at its
  // real size instead of stretching to the story viewport.
  decorators: [
    (Story) => (
      <div style={{ width: 358 }}>
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof ProjectCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = {
  args: {
    id: 'apollo',
    name: 'Project Apollo',
    team: 'Platform Team',
    status: 'healthy',
    statusLabel: 'Healthy',
    members: [{ name: 'Casey' }, { name: 'Cole' }, { name: 'Casper' }, { name: 'Extra 2' }],
    progressPercent: 82,
    totalCount: 86,
    inProgressCount: 22,
    blockedCount: 3,
    lastUpdatedAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
    href: '/projects/apollo',
  },
};

export const AtRisk: Story = {
  args: {
    ...Healthy.args,
    id: 'orbit',
    name: 'Project Orbit',
    team: 'Growth Team',
    status: 'warning',
    statusLabel: 'At Risk',
    progressPercent: 47,
    totalCount: 71,
    inProgressCount: 20,
    blockedCount: 7,
    href: '/projects/orbit',
  },
};

export const Critical: Story = {
  args: {
    ...Healthy.args,
    id: 'zeus',
    name: 'Project Zeus',
    team: 'Checkout Team',
    status: 'critical',
    statusLabel: 'Critical',
    progressPercent: 23,
    totalCount: 74,
    inProgressCount: 18,
    blockedCount: 21,
    href: '/projects/zeus',
  },
};

export const NoData: Story = {
  args: {
    ...Healthy.args,
    id: 'iris',
    name: 'Project Iris',
    team: 'Design Team',
    status: 'neutral',
    statusLabel: 'No data',
    members: [{ name: 'Casper' }],
    progressPercent: 0,
    totalCount: 12,
    inProgressCount: 2,
    blockedCount: 0,
    lastUpdatedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
    href: '/projects/iris',
  },
};
