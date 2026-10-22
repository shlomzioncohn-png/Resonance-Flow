import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useManagerProjects } from '@/hooks/useManagerProjects';
import { projectKeys, type ManagerFilters } from '@/services/queryKeys';

// Mock the API to avoid network calls
vi.mock('@/services/projectsApi', () => ({
  getProjects: vi.fn(() => Promise.resolve([])),
}));

describe('useManagerProjects hook', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // Note: Actual hook behavior tests require a QueryProvider wrapper.
  // These unit tests focus on the key generation and type contract.
  // Integration tests with rendering can be added in e2e tests.
  
  it('is properly exported', () => {
    expect(typeof useManagerProjects).toBe('function');
  });
});

describe('projectKeys with filters', () => {
  it('creates unique keys for different filters', () => {
    const key1 = projectKeys.filtered({ team: 'Frontend' });
    const key2 = projectKeys.filtered({ team: 'Backend' });

    // Keys should be different when filters differ
    expect(JSON.stringify(key1)).not.toEqual(JSON.stringify(key2));
  });

  it('creates same key for same filters', () => {
    const filters = { team: 'Frontend', project: 'P-1' };
    const key1 = projectKeys.filtered(filters);
    const key2 = projectKeys.filtered(filters);

    expect(JSON.stringify(key1)).toEqual(JSON.stringify(key2));
  });

  it('includes manager prefix for cache invalidation', () => {
    const key = projectKeys.filtered({ team: 'Frontend' });
    expect(key[0]).toBe('manager');
    expect(key[1]).toBe('projects');
  });

  it('includes undefined filters gracefully', () => {
    const key = projectKeys.filtered({ team: 'Frontend', period: undefined });
    expect(key).toEqual(['manager', 'projects', { team: 'Frontend', period: undefined }]);
  });

  it('trend key is separate from filtered keys', () => {
    const trendKey = projectKeys.trend();
    const filteredKey = projectKeys.filtered({ team: 'Frontend' });

    expect(JSON.stringify(trendKey)).not.toEqual(JSON.stringify(filteredKey));
    expect(trendKey).toEqual(['manager', 'projects', 'trend']);
  });
});

