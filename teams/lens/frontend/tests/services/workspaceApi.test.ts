import { afterEach, describe, expect, it, vi } from 'vitest';
import { selectTasks } from '@/services/workspaceApi';

describe('selectTasks', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('asks the Gateway for the selection with the available minutes', async () => {
    const selection = {
      availableMinutes: 60,
      selectedTasks: [{ id: 't1', title: 'Task t1', effortEstimate: 30, selectionScore: 0.82 }],
      totalEffortMinutes: 30,
      remainingMinutes: 30,
      usedFallback: false,
    };
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(selection),
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await selectTasks(60);

    expect(result).toEqual(selection);
    const [url] = fetchMock.mock.calls[0];
    expect(url.endsWith('/api/workspace/tasks/selection?availableMinutes=60')).toBe(true);
  });
});
