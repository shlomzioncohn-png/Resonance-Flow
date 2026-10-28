/**
 * How each task history event reads in the Activity tab. Pure functions, so
 * the wording is unit-tested on its own (tests/activity/activityText.test.ts).
 */
import type { TaskActivityItem } from '@/services/workspaceApi';

/** Chain's PENDING / ACTIVE / DONE as words: "Pending". */
function statusWord(status: string | undefined): string {
  if (!status) return 'Unknown';
  return status.charAt(0).toUpperCase() + status.slice(1).toLowerCase();
}

/** The row's main text, e.g. "Status: Pending → Active" or "Dependency added: Write the API spec". */
export function activityText(item: TaskActivityItem): string {
  switch (item.type) {
    case 'STATUS_CHANGED':
      return `Status: ${statusWord(item.fromStatus)} → ${statusWord(item.toStatus)}`;
    case 'BLOCKED': {
      const count = item.blockingCount ?? 0;
      return `Blocked by ${count} ${count === 1 ? 'task' : 'tasks'}`;
    }
    case 'UNBLOCKED':
      return 'Unblocked';
    case 'DEPENDENCY_ADDED':
      return `Dependency added: ${item.dependsOnTitle ?? 'Unknown task'}`;
    case 'DEPENDENCY_REMOVED':
      return `Dependency removed: ${item.dependsOnTitle ?? 'Unknown task'}`;
  }
}

const SOURCE_LABELS: Record<string, string> = {
  USER: 'by user',
  MIND: 'by AI',
  SYSTEM: 'by system',
};

/** Who caused a dependency change - only dependency rows have one. Never a person's name. */
export function sourceLabel(item: TaskActivityItem): string | null {
  if (!item.source) return null;
  return SOURCE_LABELS[item.source.toUpperCase()] ?? null;
}

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 60 * 60],
  ['month', 30 * 24 * 60 * 60],
  ['week', 7 * 24 * 60 * 60],
  ['day', 24 * 60 * 60],
  ['hour', 60 * 60],
  ['minute', 60],
];

const relative = new Intl.RelativeTimeFormat('en', { numeric: 'always' });

/** "2 hours ago", "3 days ago"; under a minute is "just now". */
export function relativeTime(iso: string, now: Date = new Date()): string {
  const seconds = Math.round((now.getTime() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  for (const [unit, size] of UNITS) {
    if (seconds >= size) return relative.format(-Math.floor(seconds / size), unit);
  }
  return 'just now';
}

const full = new Intl.DateTimeFormat('en-GB', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Jerusalem',
});

/** The exact time for the tooltip, in Israel time: "20 Oct 2026, 12:15". */
export function fullTime(iso: string): string {
  return full.format(new Date(iso));
}
