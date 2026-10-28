/**
 * The Dashboard charts' mock answer (GET /api/manager/dashboard/projects) -
 * used in mock mode and by the Dashboard stories. See mocks/handlers.ts.
 */
import type { ProjectTaskCounts } from '@/services/managerApi';

export const sampleProjectTaskCounts: ProjectTaskCounts[] = [
  { projectId: 'p-billing', project: 'Billing Reconciliation', inProgress: 8, snoozed: 1, open: 5, done: 11, overdue: 2 },
  { projectId: 'p-checkout', project: 'Checkout Revamp', inProgress: 6, snoozed: 1, open: 4, done: 13, overdue: 1 },
  { projectId: 'p-crm', project: 'CRM Sync Pipeline', inProgress: 1, snoozed: 3, open: 8, done: 1, overdue: 6 },
  { projectId: 'p-fraud', project: 'Fraud Detection API', inProgress: 9, snoozed: 2, open: 7, done: 9, overdue: 3 },
  { projectId: 'p-mobile', project: 'Mobile Onboarding', inProgress: 3, snoozed: 4, open: 6, done: 3, overdue: 5 },
  { projectId: 'p-search', project: 'Search Relevance v2', inProgress: 2, snoozed: 0, open: 1, done: 11, overdue: 0 },
];
