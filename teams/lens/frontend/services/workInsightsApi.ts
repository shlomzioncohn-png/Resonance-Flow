/**
 * Work Insights API - typed wrapper around the Gateway's /api/work-insights endpoints.
 * Not served yet: the Gateway, BFF and Inbox endpoints are built after this screen.
 */
import { api } from './api';

export type WorkInsightStatus = 'READY' | 'NOT_ENOUGH_DATA';

export interface InsightEvidence {
  text: string;
  achieved: number;
  total: number;
}

export interface WorkInsight {
  insightId: string;
  status: WorkInsightStatus;
  /** Length of the analyzed period, in days */
  periodDays: number;
  /** Present only when status is READY */
  insight?: string;
  /** Present only when the data supports a recommendation */
  recommendation?: string;
  /** Present only when status is READY */
  evidence?: InsightEvidence;
}

export interface InsightFeedbackRequest {
  helpful: boolean;
}

export function getWorkInsight(): Promise<WorkInsight> {
  return api.get<WorkInsight>('/api/work-insights');
}

export function sendInsightFeedback(insightId: string, request: InsightFeedbackRequest): Promise<void> {
  return api.post<void>(`/api/work-insights/${insightId}/feedback`, request);
}
