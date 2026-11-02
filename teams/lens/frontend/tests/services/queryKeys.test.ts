import { describe, expect, it } from 'vitest';
import { taskKeys, projectKeys, bottleneckKeys, type ManagerFilters } from '@/services/queryKeys';

describe('query keys', () => {
  // Invalidating a root key only reaches the keys that start with it
  it.each([
    ['taskKeys.detail', taskKeys.detail('T-1'), taskKeys.all],
    ['taskKeys.recommendation', taskKeys.recommendation({ availableTime: 30 }), taskKeys.all],
    ['projectKeys.trend', projectKeys.trend(), projectKeys.all],
  ])('%s starts with its root key', (_name, key, root) => {
    expect(key.slice(0, root.length)).toEqual(root);
  });

  it('builds a task detail key from the task id', () => {
    expect(taskKeys.detail('T-1')).toEqual(['tasks', 'detail', 'T-1']);
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

    // A constraint change invalidates graphs() - it must reach every project's graph.
    it.each([undefined, 'P-123'])('graph(%s) starts with graphs()', (projectId) => {
      const graphs = bottleneckKeys.graphs();
      expect(bottleneckKeys.graph(projectId).slice(0, graphs.length)).toEqual(graphs);
    });

    it("doesn't put a task's constraints under graphs()", () => {
      const graphs = bottleneckKeys.graphs();
      expect(bottleneckKeys.constraints('t-1').slice(0, graphs.length)).not.toEqual(graphs);
    });
  });
});
