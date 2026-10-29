/**
 * Sample flow-health flags — the one source for the MSW handler (mocks/handlers.ts)
 * and the Flow Health stories. Task ids are the sample dependency graph's, so the
 * chronic-blocker drawer finds the tasks it blocks.
 */
import type { FlowFlag, FlowHealth, FlowPattern } from '@/services/flowHealthApi';
import { sampleGraph } from './dependencyGraphData';

const title = (index: number) => sampleGraph.nodes[index].title;
const taskId = (index: number) => sampleGraph.nodes[index].id;
const task = (index: number) => ({ id: taskId(index), title: title(index) });
const entity = (index: number) => ({ id: taskId(index), label: title(index), tasks: [task(index)] });
const edge = (dependent: number, blocker: number) => ({
  id: `${taskId(dependent)}>${taskId(blocker)}`,
  label: `${title(dependent)} → ${title(blocker)}`,
  tasks: [task(dependent), task(blocker)],
});

const PLATFORM = { id: 'p3', name: 'Mobile Onboarding' };
const CRM = { id: 'p7', name: 'CRM Sync Pipeline' };

const sampleFlags: FlowFlag[] = [
  {
    flagId: 'CHRONIC_BLOCKER:4230f2a0b8',
    rank: 1,
    pattern: 'CHRONIC_BLOCKER',
    entityType: 'task',
    entities: [entity(0)],
    severity: 'high',
    score: 0.96,
    explanation: 'Worked on for 41 days, 3.4x the median of its tier (12 days), blocks 12 tasks',
    evidence: {
      days_active: 41,
      tier: 'MEDIUM',
      tier_median_active_days: 12,
      ratio_to_tier_median: 3.4,
      n_dependents_max: 12,
      is_open: true,
      days_blocked: 9,
    },
    projects: [PLATFORM],
  },
  {
    flagId: 'RECURRING_CYCLE:81ad03c5e1',
    rank: 2,
    pattern: 'RECURRING_CYCLE',
    entityType: 'edge',
    entities: [edge(1, 0)],
    severity: 'high',
    score: 0.88,
    explanation: 'The edge switched 5 times within 36 days (median gap 6 days)',
    evidence: {
      transitions: 5,
      span_days: 36,
      median_gap_days: 6,
      gap_cv: 0.4,
      gaps_days: [4, 7, 6, 9],
      last_action: 'ADDED',
    },
    projects: [PLATFORM],
  },
  {
    flagId: 'SLOW_CHAIN:c7e19a44d2',
    rank: 3,
    pattern: 'SLOW_CHAIN',
    entityType: 'chain',
    entities: [entity(3), entity(8), entity(12)],
    severity: 'medium',
    score: 0.71,
    explanation: 'A chain of 3 tasks took 58 days end to end, with no link accounting for more than 60 percent of the time',
    evidence: {
      chain_length: 3,
      end_to_end_days: 58,
      max_link_share: 0.52,
      sink_task_id: taskId(12),
      link_days: [14, 30, 14],
    },
    projects: [CRM],
  },
  {
    flagId: 'RECURRING_CYCLE:5b02e7a9f0',
    rank: 4,
    pattern: 'RECURRING_CYCLE',
    entityType: 'edge_pair',
    entities: [edge(4, 0), edge(4, 2)],
    severity: 'low',
    score: 0.35,
    explanation: 'Two edges of one task: one was removed and came back after 11 days, while the other stayed active',
    evidence: {
      broken_edge: `${taskId(4)}>${taskId(0)}`,
      kept_edge: `${taskId(4)}>${taskId(2)}`,
      removed_at: '2026-09-20T09:00:00Z',
      returned_at: '2026-10-01T09:00:00Z',
      days_away: 11,
    },
    projects: [PLATFORM],
  },
  {
    flagId: 'CHRONIC_BLOCKER:a90c11d7b3',
    rank: 5,
    pattern: 'CHRONIC_BLOCKER',
    entityType: 'task',
    entities: [entity(2)],
    severity: 'low',
    score: 0.24,
    explanation: 'Worked on for 19 days, 1.6x the median of its tier (12 days), blocks 2 tasks',
    evidence: {
      days_active: 19,
      tier: 'MEDIUM',
      tier_median_active_days: 12,
      ratio_to_tier_median: 1.6,
      n_dependents_max: 2,
      is_open: false,
      days_blocked: 3,
    },
    projects: [PLATFORM],
  },
];

function summarize(flags: FlowFlag[]): FlowHealth {
  const counts = { CHRONIC_BLOCKER: 0, RECURRING_CYCLE: 0, SLOW_CHAIN: 0 } satisfies Record<FlowPattern, number>;
  for (const flag of flags) counts[flag.pattern] += 1;
  const taskNames = Object.fromEntries(
    flags.flatMap((flag) => flag.entities.flatMap((e) => e.tasks.map((t) => [t.id, t.title]))),
  );
  const projects = [...new Map(flags.flatMap((flag) => flag.projects).map((p) => [p.id, p])).values()];
  return { lastRunAt: flags.length > 0 ? '2026-10-28T02:00:00Z' : null, counts, flags, taskNames, projects };
}

export const sampleFlowHealth: FlowHealth = summarize(sampleFlags);

/** The job has not produced any flags yet: the page shows "not enough history". */
export const emptyFlowHealth: FlowHealth = summarize([]);
