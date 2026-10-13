import { describe, expect, it } from 'vitest';
import { taskKeys, workloadKeys } from '@/services/queryKeys';

describe('query keys', () => {
  // Invalidating a root key only reaches the keys that start with it
  it.each([
    ['taskKeys.detail', taskKeys.detail('T-1'), taskKeys.all],
    ['taskKeys.recommendation', taskKeys.recommendation({ availableTime: 30 }), taskKeys.all],
    ['workloadKeys.all', workloadKeys.all, workloadKeys.all],
  ])('%s starts with its root key', (_name, key, root) => {
    expect(key.slice(0, root.length)).toEqual(root);
  });

  it('builds a task detail key from the task id', () => {
    expect(taskKeys.detail('T-1')).toEqual(['tasks', 'detail', 'T-1']);
  });
});
