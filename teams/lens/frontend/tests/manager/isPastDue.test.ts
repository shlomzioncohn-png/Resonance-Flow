import { describe, expect, it } from 'vitest';
import { isPastDue } from '@/app/(app)/manager/ManagerTaskTable';

describe('isPastDue', () => {
  const today = new Date(2026, 9, 21, 15, 30); // 21/10/2026, afternoon

  it.each([
    ['20/10/2026', true], // yesterday
    ['21/10/2026', false], // due today: not late yet
    ['22/10/2026', false],
    ['31/12/2025', true],
    [undefined, false],
    ['2026-10-01', false], // not Inbox's format: never counted as late
  ])('%s -> %s', (dueDate, expected) => {
    expect(isPastDue(dueDate, today)).toBe(expected);
  });
});
