/**
 * Pure logic of the Flow Health screen: labels, filtering, and reading the
 * detector-specific `evidence` of a flag. No React here, so it is unit-tested
 * on its own (tests/services/flowHealthModel.test.ts).
 */
import type { StatusTone } from '@/components/ui/StatusBadge';
import type { FlowFlag, FlowPattern, FlowSeverity } from '@/services/flowHealthApi';

export const PATTERNS: FlowPattern[] = ['CHRONIC_BLOCKER', 'RECURRING_CYCLE', 'SLOW_CHAIN'];
export const SEVERITIES: FlowSeverity[] = ['high', 'medium', 'low'];

export const PATTERN_LABEL: Record<FlowPattern, string> = {
  CHRONIC_BLOCKER: 'Chronic blocker',
  RECURRING_CYCLE: 'Blocker cycle',
  SLOW_CHAIN: 'Slow chain',
};

export const SEVERITY_LABEL: Record<FlowSeverity, string> = {
  high: 'High',
  medium: 'Medium',
  low: 'Low',
};

export const SEVERITY_TONE: Record<FlowSeverity, StatusTone> = {
  high: 'critical',
  medium: 'warning',
  low: 'neutral',
};

export type FlowFilters = {
  pattern?: FlowPattern;
  severity?: FlowSeverity;
  projectId?: string;
};

/** Flags matching every given filter, in rank order. */
export function filterFlags(flags: FlowFlag[], { pattern, severity, projectId }: FlowFilters): FlowFlag[] {
  return flags
    .filter((flag) => !pattern || flag.pattern === pattern)
    .filter((flag) => !severity || flag.severity === severity)
    .filter((flag) => !projectId || flag.projects.some((project) => project.id === projectId))
    .sort((a, b) => a.rank - b.rank);
}

/** A value from a URL query, kept only when it is one of the allowed values. */
export function parseOneOf<T extends string>(value: string | null, allowed: readonly T[]): T | undefined {
  return allowed.find((option) => option === value);
}

export function evidenceNumber(evidence: FlowFlag['evidence'], key: string): number | undefined {
  const value = evidence?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

export function evidenceString(evidence: FlowFlag['evidence'], key: string): string | undefined {
  const value = evidence?.[key];
  return typeof value === 'string' && value !== '' ? value : undefined;
}

function evidenceNumbers(evidence: FlowFlag['evidence'], key: string): number[] | undefined {
  const value = evidence?.[key];
  if (!Array.isArray(value) || !value.every((item) => typeof item === 'number' && Number.isFinite(item))) {
    return undefined;
  }
  return value as number[];
}

/** A flag's entities as one line: a chain reads in order (→); separate tasks or edges are listed (;). */
export function entityLabel(flag: FlowFlag): string {
  return flag.entities.map((entity) => entity.label).join(flag.entityType === 'chain' ? ' → ' : '; ');
}

export type CycleState ='blocked' | 'released';

export type CycleStep = {
  state: CycleState;
  /** How long the edge stayed in this state; null for the last, still-open one. */
  days: number | null;
};

/**
 * The block/release steps of a flapping edge, oldest first. The evidence holds the
 * number of switches, the gaps between them and the last action, so the earlier
 * states are walked back from the last one: ADDED means blocking, REMOVED means released.
 */
export function cycleSteps(evidence: FlowFlag['evidence']): CycleStep[] {
  const transitions = evidenceNumber(evidence, 'transitions');
  if (!transitions || transitions < 1) return [];
  const gaps = evidenceNumbers(evidence, 'gaps_days') ?? [];
  const median = evidenceNumber(evidence, 'median_gap_days') ?? 0;
  const last: CycleState = evidenceString(evidence, 'last_action') === 'REMOVED' ? 'released' : 'blocked';
  const other: CycleState = last === 'blocked' ? 'released' : 'blocked';

  return Array.from({ length: transitions }, (_, index) => {
    const fromEnd = transitions - 1 - index;
    return {
      state: fromEnd % 2 === 0 ? last : other,
      days: fromEnd === 0 ? null : (gaps[index] ?? median),
    };
  });
}

export type ChainLink = {
  taskId: string;
  title: string;
  /** null when this run's evidence has no per-link durations. */
  days: number | null;
};

/** The links of a slow chain in order, with their durations when the evidence carries `link_days`. */
export function chainLinks(flag: FlowFlag): ChainLink[] {
  const linkDays = evidenceNumbers(flag.evidence, 'link_days');
  const aligned = linkDays && linkDays.length === flag.entities.length ? linkDays : undefined;
  return flag.entities.map((entity, index) => ({
    taskId: entity.tasks[0]?.id ?? entity.id,
    title: entity.label,
    days: aligned ? aligned[index] : null,
  }));
}

/** Short date for the "last run" caption; empty string for a missing or unparsable value. */
export function formatRunDate(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}
