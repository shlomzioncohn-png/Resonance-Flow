/**
 * Flow Health API — typed wrapper around the Gateway's read-only flow-health
 * flags (Chain's analytics job, enriched with task names by the BFF). There
 * is no write call here on purpose: the page is a recommendation only.
 */
import { api } from './api';

export type FlowPattern = 'CHRONIC_BLOCKER' | 'RECURRING_CYCLE' | 'SLOW_CHAIN';
export type FlowSeverity = 'low' | 'medium' | 'high';
export type FlowEntityType = 'task' | 'edge' | 'edge_pair' | 'chain';

type FlowTaskRef = {
  id: string;
  /** The Inbox title, or the id itself when Inbox doesn't know the task. */
  title: string;
};

/** One element of a flag's entity ids: a task, or an edge (dependent first, then the task it waits on). */
export type FlowEntity = {
  id: string;
  label: string;
  tasks: FlowTaskRef[];
};

export type FlowProject = {
  id: string;
  name: string;
};

export type FlowFlag = {
  flagId: string;
  /** Overall order assigned by the job; 1 is the most important. */
  rank: number;
  pattern: FlowPattern;
  entityType: FlowEntityType;
  entities: FlowEntity[];
  severity: FlowSeverity;
  /** 0-1 percentile within the detector: only comparable inside one pattern. */
  score: number;
  explanation: string;
  /** Detector-specific details; read it through flowHealthModel's helpers, not directly. */
  evidence: Record<string, unknown> | null;
  projects: FlowProject[];
};

export type FlowHealth = {
  /** null when the job has produced no flags yet. */
  lastRunAt: string | null;
  counts: Record<FlowPattern, number>;
  /** Ordered by rank. */
  flags: FlowFlag[];
  /** Title of every task id found in the flags' entities or evidence. */
  taskNames: Record<string, string>;
  projects: FlowProject[];
};

export function getFlowHealth(): Promise<FlowHealth> {
  return api.get<FlowHealth>('/api/manager/flow-health');
}
