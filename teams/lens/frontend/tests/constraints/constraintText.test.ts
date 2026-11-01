import { describe, expect, it } from 'vitest';
import {
  constraintStatusLabel,
  constraintTypeLabel,
  isOpenConstraint,
  reasonEntries,
} from '@/app/(app)/employee/tasks/[taskId]/constraintText';

describe('constraintTypeLabel', () => {
  it('maps Chain types to display text', () => {
    expect(constraintTypeLabel('APPROVAL_NEEDED')).toBe('Approval needed');
    expect(constraintTypeLabel('BLOCKED_BY_INFO')).toBe('Missing info');
    expect(constraintTypeLabel('DATE_WINDOW')).toBe('Date window');
  });

  it('shows an unknown type as sent', () => {
    expect(constraintTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('constraintStatusLabel', () => {
  it('maps Chain statuses to display text', () => {
    expect(constraintStatusLabel('PENDING')).toBe('Pending');
    expect(constraintStatusLabel('SATISFIED')).toBe('Satisfied');
    expect(constraintStatusLabel('WAIVED')).toBe('Waived');
  });
});

describe('isOpenConstraint', () => {
  it('only PENDING is open', () => {
    expect(isOpenConstraint({ status: 'PENDING' })).toBe(true);
    expect(isOpenConstraint({ status: 'SATISFIED' })).toBe(false);
    expect(isOpenConstraint({ status: 'WAIVED' })).toBe(false);
  });
});

describe('reasonEntries', () => {
  it('lists "key: value" in order', () => {
    expect(reasonEntries({ approver: 'Legal', attempts: 2, urgent: true })).toEqual([
      'approver: Legal',
      'attempts: 2',
      'urgent: true',
    ]);
  });

  it('empty, missing or null reason -> nothing', () => {
    expect(reasonEntries({})).toEqual([]);
    expect(reasonEntries(undefined)).toEqual([]);
    expect(reasonEntries(null)).toEqual([]);
  });

  it('a null value shows as a dash', () => {
    expect(reasonEntries({ note: null })).toEqual(['note: —']);
  });
});
