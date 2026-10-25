import type { Meta, StoryObj } from '@storybook/nextjs-vite';
import { fn } from 'storybook/test';
import type { WorkInsight } from '@/services/workInsightsApi';
import WorkInsightsDrawer from './WorkInsightsDrawer';

const READY_INSIGHT: WorkInsight = {
  insightId: 'b3c1f0e2-7a4d-4c1b-9e2f-1a2b3c4d5e6f',
  status: 'READY',
  periodDays: 30,
  insight: 'You finished most of your tasks before the deadline this month. Well done!',
  recommendation: 'Splitting long tasks into steps, where estimates still drift.',
  evidence: { text: '12 of 15 tasks done before the deadline', achieved: 12, total: 15 },
};

const meta = {
  title: 'UI/WorkInsightsDrawer',
  component: WorkInsightsDrawer,
  tags: ['autodocs'],
  args: {
    open: true,
    onClose: fn(),
    onRetry: fn(),
    onFeedback: fn(),
    state: 'loaded',
    insight: READY_INSIGHT,
    errorMessage: '',
    feedbackSent: false,
  },
} satisfies Meta<typeof WorkInsightsDrawer>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Loading: Story = {
  args: { state: 'loading', insight: null },
};

export const Insight: Story = {};

export const WithoutRecommendation: Story = {
  args: { insight: { ...READY_INSIGHT, recommendation: undefined } },
};

export const FeedbackSent: Story = {
  args: { feedbackSent: true },
};

export const NotEnoughData: Story = {
  args: {
    insight: { insightId: 'c4d2a1b3-8e5f-4a2c-b1d3-2b3c4d5e6f70', status: 'NOT_ENOUGH_DATA', periodDays: 30 },
  },
};

export const LoadFailed: Story = {
  args: {
    state: 'error',
    insight: null,
    errorMessage: 'The task service is temporarily unavailable. Please try again in a moment.',
  },
};
