/**
 * Work Insights API - typed wrapper around the Gateway's /api/work-insights endpoints.
 * The Gateway and BFF forward them to Inbox.
 */
import { api } from './api';

type WorkInsightStatus = 'READY' | 'NOT_ENOUGH_DATA' | 'NO_INSIGHT';

type InsightEvidence = {
  text: string;
  /** Present only when the evidence is an "X of Y" count */
  achieved?: number;
  /** Present only when the evidence is an "X of Y" count */
  total?: number;
};

export type WorkInsight = {
  /** Present only when status is READY */
  insightId?: string;
  status: WorkInsightStatus;
  /** Length of the analyzed period, in days */
  periodDays: number;
  /** Present only when status is READY */
  insight?: string;
  /** Present only when the data supports a recommendation */
  recommendation?: string;
  /** Present only when status is READY */
  evidence?: InsightEvidence;
};

export type InsightFeedbackRequest = {
  helpful: boolean;
};

export function getWorkInsight(): Promise<WorkInsight> {
  return api.get<WorkInsight>('/api/work-insights');
}

export function sendInsightFeedback(insightId: string, request: InsightFeedbackRequest): Promise<void> {
  return api.post<void>(`/api/work-insights/${insightId}/feedback`, request);
}
