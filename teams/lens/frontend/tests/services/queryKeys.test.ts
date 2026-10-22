import { describe, expect, it } from 'vitest';
import { taskKeys, workloadKeys, projectKeys, bottleneckKeys, type ManagerFilters } from '@/services/queryKeys';

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

  describe('workloadKeys with filters', () => {
    it('builds a key without filters', () => {
      expect(workloadKeys.filtered()).toEqual(['manager', 'workload', {}]);
    });

    it('builds a key with filters', () => {
      const filters: ManagerFilters = { period: '2026-W33', team: 'Backend', project: 'P-1' };
      const key = workloadKeys.filtered(filters);
      expect(key[0]).toBe('manager');
      expect(key[1]).toBe('workload');
      expect(key[2]).toEqual(filters);
    });

    it('includes only specified filters', () => {
      const key = workloadKeys.filtered({ team: 'Frontend' });
      expect(key[2]).toEqual({ team: 'Frontend' });
    });

    it('differentiates keys by different filters', () => {
      const key1 = workloadKeys.filtered({ team: 'Frontend' });
      const key2 = workloadKeys.filtered({ team: 'Backend' });
      expect(key1[2]).not.toEqual(key2[2]);
    });
  });

  describe('projectKeys with filters', () => {
    it('builds a key without filters', () => {
      expect(projectKeys.filtered()).toEqual(['manager', 'projects', {}]);
    });

    it('builds a key with filters', () => {
      const filters: ManagerFilters = { period: '2026-W33', team: 'Backend' };
      const key = projectKeys.filtered(filters);
      expect(key[0]).toBe('manager');
      expect(key[1]).toBe('projects');
      expect(key[2]).toEqual(filters);
    });

    it('builds a trend key', () => {
      const key = projectKeys.trend();
      expect(key).toEqual(['manager', 'projects', 'trend']);
    });
  });

  describe('bottleneckKeys', () => {
    it('builds a graph key without project', () => {
      expect(bottleneckKeys.graph()).toEqual(['manager', 'bottlenecks', 'graph', 'all']);
    });

    it('builds a graph key with project', () => {
      expect(bottleneckKeys.graph('P-123')).toEqual(['manager', 'bottlenecks', 'graph', 'P-123']);
    });
  });
});
