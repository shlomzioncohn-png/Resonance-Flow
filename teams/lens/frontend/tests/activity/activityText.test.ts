import { describe, expect, it } from 'vitest';
import {
  activityText,
  fullTime,
  relativeTime,
  sourceLabel,
} from '@/app/(app)/employee/tasks/[taskId]/activityText';
import type { TaskActivityItem } from '@/services/workspaceApi';

const at = '2026-10-20T09:15:30Z';
const item = (
  fields: Partial<TaskActivityItem> & Pick<TaskActivityItem, 'type'>,
): TaskActivityItem => ({
  id: 'e1',
  createdAt: at,
  ...fields,
});

describe('activityText', () => {
  it('STATUS_CHANGED: "Status: Pending → Active"', () => {
    expect(
      activityText(item({ type: 'STATUS_CHANGED', fromStatus: 'PENDING', toStatus: 'ACTIVE' })),
    ).toBe('Status: Pending → Active');
    expect(
      activityText(item({ type: 'STATUS_CHANGED', fromStatus: 'ACTIVE', toStatus: 'DONE' })),
    ).toBe('Status: Active → Done');
  });

  it('BLOCKED: "Blocked by N tasks", singular for one', () => {
    expect(activityText(item({ type: 'BLOCKED', blockingCount: 2 }))).toBe('Blocked by 2 tasks');
    expect(activityText(item({ type: 'BLOCKED', blockingCount: 1 }))).toBe('Blocked by 1 task');
  });

  it('UNBLOCKED: "Unblocked"', () => {
    expect(activityText(item({ type: 'UNBLOCKED', blockingCount: 0 }))).toBe('Unblocked');
  });

  it('DEPENDENCY_ADDED / REMOVED show the task title, not its id', () => {
    const fields = { dependsOnTaskId: 'b6e2…', dependsOnTitle: 'Write the API spec' };
    expect(activityText(item({ type: 'DEPENDENCY_ADDED', ...fields }))).toBe(
      'Dependency added: Write the API spec',
    );
    expect(activityText(item({ type: 'DEPENDENCY_REMOVED', ...fields }))).toBe(
      'Dependency removed: Write the API spec',
    );
  });

  it('a dependency without a title says "Unknown task"', () => {
    expect(activityText(item({ type: 'DEPENDENCY_ADDED', dependsOnTaskId: 'x' }))).toBe(
      'Dependency added: Unknown task',
    );
  });
});

describe('sourceLabel', () => {
  it('maps USER / MIND / SYSTEM, and only dependency rows have one', () => {
    expect(sourceLabel(item({ type: 'DEPENDENCY_ADDED', source: 'USER' }))).toBe('by user');
    expect(sourceLabel(item({ type: 'DEPENDENCY_ADDED', source: 'MIND' }))).toBe('by AI');
    expect(sourceLabel(item({ type: 'DEPENDENCY_REMOVED', source: 'SYSTEM' }))).toBe('by system');
    expect(sourceLabel(item({ type: 'STATUS_CHANGED' }))).toBeNull();
  });
});

describe('relativeTime', () => {
  const now = new Date('2026-10-20T12:00:00Z');

  it('reads like "2 hours ago"', () => {
    expect(relativeTime('2026-10-20T10:00:00Z', now)).toBe('2 hours ago');
    expect(relativeTime('2026-10-20T11:59:30Z', now)).toBe('just now');
    expect(relativeTime('2026-10-20T11:55:00Z', now)).toBe('5 minutes ago');
    expect(relativeTime('2026-10-17T12:00:00Z', now)).toBe('3 days ago');
    expect(relativeTime('2026-10-06T12:00:00Z', now)).toBe('2 weeks ago');
  });
});

describe('fullTime', () => {
  it('is the exact time in Israel time, for the tooltip', () => {
    // 09:15 UTC is 12:15 in Israel (IDT, UTC+3) on 20 Oct 2026.
    expect(fullTime(at)).toBe('20 Oct 2026, 12:15');
  });
});
