import type { Constraint } from '@/services/workspaceApi';

const TYPE_LABELS: Record<string, string> = {
  APPROVAL_NEEDED: 'Approval needed',
  BLOCKED_BY_INFO: 'Missing info',
  DATE_WINDOW: 'Date window',
};

const STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pending',
  SATISFIED: 'Satisfied',
  WAIVED: 'Waived',
};

/** "Approval needed" / "Missing info" / "Date window". An unknown type is shown as Chain sent it. */
export function constraintTypeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type;
}

/** "Pending" / "Satisfied" / "Waived". An unknown status is shown as Chain sent it. */
export function constraintStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

/** Still open - only PENDING blocks the task. */
export function isOpenConstraint(constraint: Pick<Constraint, 'status'>): boolean {
  return constraint.status === 'PENDING';
}

/**
 * The reason as "key: value" lines, in Chain's order. Missing or empty -> no lines.
 * Values are flat scalars (Chain rejects nested ones); null shows as "—".
 */
export function reasonEntries(reason?: Record<string, unknown> | null): string[] {
  if (!reason) return [];
  return Object.entries(reason).map(
    ([key, value]) => `${key}: ${value == null ? '—' : String(value)}`,
  );
}
