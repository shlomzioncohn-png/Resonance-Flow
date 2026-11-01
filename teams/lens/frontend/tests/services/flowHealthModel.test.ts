import { describe, expect, it } from 'vitest';
import {
  chainLinks,
  cycleSteps,
  entityLabel,
  evidenceNumber,
  filterFlags,
  formatRunDate,
  parseOneOf,
  PATTERNS,
} from '@/app/(app)/manager/flow-health/flowHealthModel';
import type { FlowFlag } from '@/services/flowHealthApi';

function flag(overrides: Partial<FlowFlag> & Pick<FlowFlag, 'flagId' | 'rank'>): FlowFlag {
  return {
    pattern: 'CHRONIC_BLOCKER',
    entityType: 'task',
    entities: [],
    severity: 'low',
    score: 0.5,
    explanation: '',
    evidence: null,
    projects: [],
    ...overrides,
  };
}

describe('filterFlags', () => {
  const flags = [
    flag({ flagId: 'c', rank: 3, pattern: 'SLOW_CHAIN', severity: 'high', projects: [{ id: 'p1', name: 'One' }] }),
    flag({ flagId: 'a', rank: 1, pattern: 'CHRONIC_BLOCKER', severity: 'high', projects: [{ id: 'p2', name: 'Two' }] }),
    flag({ flagId: 'b', rank: 2, pattern: 'CHRONIC_BLOCKER', severity: 'low' }),
  ];

  it('returns every flag in rank order when no filter is set', () => {
    expect(filterFlags(flags, {}).map((f) => f.flagId)).toEqual(['a', 'b', 'c']);
  });

  it('filters by pattern, severity and project together', () => {
    expect(filterFlags(flags, { pattern: 'CHRONIC_BLOCKER' }).map((f) => f.flagId)).toEqual(['a', 'b']);
    expect(filterFlags(flags, { severity: 'high' }).map((f) => f.flagId)).toEqual(['a', 'c']);
    expect(filterFlags(flags, { projectId: 'p1' }).map((f) => f.flagId)).toEqual(['c']);
    expect(filterFlags(flags, { pattern: 'CHRONIC_BLOCKER', severity: 'high', projectId: 'p2' })).toHaveLength(1);
  });

  it('does not reorder or change the input array', () => {
    const copy = [...flags];
    filterFlags(flags, {});
    expect(flags).toEqual(copy);
  });
});

describe('entityLabel', () => {
  const entities = [
    { id: 'a', label: 'A', tasks: [] },
    { id: 'b', label: 'B', tasks: [] },
  ];

  it('joins a chain in order with arrows', () => {
    expect(entityLabel(flag({ flagId: 'x', rank: 1, entityType: 'chain', entities }))).toBe('A → B');
  });

  it('lists separate edges instead of reading them as a path', () => {
    expect(entityLabel(flag({ flagId: 'x', rank: 1, entityType: 'edge_pair', entities }))).toBe('A; B');
  });
});

describe('parseOneOf', () => {
  it('keeps an allowed value and drops anything else', () => {
    expect(parseOneOf('SLOW_CHAIN', PATTERNS)).toBe('SLOW_CHAIN');
    expect(parseOneOf('nope', PATTERNS)).toBeUndefined();
    expect(parseOneOf(null, PATTERNS)).toBeUndefined();
  });
});

describe('evidenceNumber', () => {
  it('reads finite numbers only', () => {
    expect(evidenceNumber({ days: 4 }, 'days')).toBe(4);
    expect(evidenceNumber({ days: '4' }, 'days')).toBeUndefined();
    expect(evidenceNumber(null, 'days')).toBeUndefined();
  });
});

describe('cycleSteps', () => {
  it('walks the states back from the last action, with the gaps between switches', () => {
    // 4 switches ending in REMOVED: blocked, released, blocked, released (open)
    const steps = cycleSteps({ transitions: 4, gaps_days: [2, 3, 5], median_gap_days: 3, last_action: 'REMOVED' });

    expect(steps).toEqual([
      { state: 'blocked', days: 2 },
      { state: 'released', days: 3 },
      { state: 'blocked', days: 5 },
      { state: 'released', days: null },
    ]);
  });

  it('starts with a block when the last action is ADDED after an even number of switches', () => {
    const steps = cycleSteps({ transitions: 3, gaps_days: [1, 1], median_gap_days: 1, last_action: 'ADDED' });

    expect(steps.map((step) => step.state)).toEqual(['blocked', 'released', 'blocked']);
  });

  it('falls back to the median gap when the gaps are missing', () => {
    const steps = cycleSteps({ transitions: 3, median_gap_days: 4, last_action: 'ADDED' });

    expect(steps.map((step) => step.days)).toEqual([4, 4, null]);
  });

  it('is empty without usable evidence', () => {
    expect(cycleSteps(null)).toEqual([]);
    expect(cycleSteps({ transitions: 0 })).toEqual([]);
  });
});

describe('chainLinks', () => {
  const entities = [
    { id: 't1', label: 'Design', tasks: [{ id: 't1', title: 'Design' }] },
    { id: 't2', label: 'Build', tasks: [{ id: 't2', title: 'Build' }] },
  ];

  it('adds the per-link durations when the evidence has one per task', () => {
    const links = chainLinks(flag({ flagId: 'x', rank: 1, pattern: 'SLOW_CHAIN', entities, evidence: { link_days: [3, 9] } }));

    expect(links).toEqual([
      { taskId: 't1', title: 'Design', days: 3 },
      { taskId: 't2', title: 'Build', days: 9 },
    ]);
  });

  it('leaves the durations null when the evidence has none, or the wrong number of them', () => {
    expect(chainLinks(flag({ flagId: 'x', rank: 1, entities })).map((link) => link.days)).toEqual([null, null]);
    expect(
      chainLinks(flag({ flagId: 'x', rank: 1, entities, evidence: { link_days: [3] } })).map((link) => link.days),
    ).toEqual([null, null]);
  });
});

describe('formatRunDate', () => {
  it('formats an ISO instant as a UTC date', () => {
    expect(formatRunDate('2026-10-28T23:30:00Z')).toBe('28 Oct 2026');
  });

  it('is empty for a missing or invalid value', () => {
    expect(formatRunDate(null)).toBe('');
    expect(formatRunDate('not a date')).toBe('');
  });
});
