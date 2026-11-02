/**
 * Sample dependency graphs for the Bottlenecks screen — the one source for
 * both the MSW handler (mocks/handlers.ts) and the DependencyGraph stories,
 * so the app in mock mode and Storybook always show the same data.
 *
 * Only tasks and edges are written by hand. `isBlocked` and `blocksCount` are
 * computed from the edges (buildGraph), so they can never disagree with them.
 */
import type {
  DependencyGraph,
  GraphEdge,
  GraphNode,
  GraphTaskStatus,
} from '@/services/bottlenecksApi';

type SeedTask = {
  id: string;
  title: string;
  status: GraphTaskStatus;
  /** The BFF's mock project id (p1-p8), so the project filter has something to filter. */
  projectId: string;
  /** Open (PENDING) constraints; they block the task too. Default 0. */
  openConstraints?: number;
};

type SeedEdge = [source: string, target: string, confidence: number];

/** Every task that depends on `id`, directly or indirectly. */
function dependentsOf(id: string, blocks: Map<string, string[]>, seen = new Set<string>()): Set<string> {
  for (const target of blocks.get(id) ?? []) {
    if (!seen.has(target)) {
      seen.add(target);
      dependentsOf(target, blocks, seen);
    }
  }
  return seen;
}

function buildGraph(tasks: SeedTask[], seedEdges: SeedEdge[]): DependencyGraph {
  const ids = new Set(tasks.map((task) => task.id));
  const edges: GraphEdge[] = seedEdges
    .filter(([source, target]) => ids.has(source) && ids.has(target))
    .map(([source, target, confidence]) => ({ source, target, confidence }));
  const statusOf = new Map(tasks.map((task) => [task.id, task.status]));
  const blocks = new Map<string, string[]>();
  for (const edge of edges) blocks.set(edge.source, [...(blocks.get(edge.source) ?? []), edge.target]);

  const nodes: GraphNode[] = tasks.map((task) => ({
    id: task.id,
    title: task.title,
    status: task.status,
    // Same rule as Chain's isReady: an unfinished dependency or an open constraint.
    isBlocked:
      (task.openConstraints ?? 0) > 0 ||
      edges.some((edge) => edge.target === task.id && statusOf.get(edge.source) !== 'DONE'),
    blocksCount: dependentsOf(task.id, blocks).size,
    openConstraintCount: task.openConstraints ?? 0,
  }));
  return { nodes, edges };
}

const id = (n: number) => `5f3c2a10-8d4e-4b7a-9c61-0000000000${String(n).padStart(2, '0')}`;

const SAMPLE_TASKS: SeedTask[] = [
  { id: id(1), title: 'Finalize auth token service', status: 'ACTIVE', projectId: 'p3' },
  { id: id(2), title: 'Migrate login screen', status: 'PENDING', projectId: 'p3', openConstraints: 2 },
  { id: id(3), title: 'Session refresh API', status: 'PENDING', projectId: 'p3' },
  { id: id(4), title: 'SSO for partners', status: 'PENDING', projectId: 'p7' },
  { id: id(5), title: 'Onboarding flow v2', status: 'PENDING', projectId: 'p3' },
  { id: id(6), title: 'Password reset emails', status: 'PENDING', projectId: 'p3' },
  { id: id(7), title: 'Mobile app session sync', status: 'PENDING', projectId: 'p3' },
  { id: id(8), title: 'Audit log for sign-ins', status: 'PENDING', projectId: 'p3' },
  { id: id(9), title: 'Partner portal launch', status: 'PENDING', projectId: 'p7' },
  { id: id(10), title: 'Onboarding analytics', status: 'PENDING', projectId: 'p3' },
  { id: id(11), title: 'Welcome email series', status: 'PENDING', projectId: 'p3' },
  { id: id(12), title: 'Offline mode', status: 'PENDING', projectId: 'p3' },
  { id: id(13), title: 'Partner billing setup', status: 'PENDING', projectId: 'p7' },
  { id: id(14), title: 'Design tokens v2', status: 'DONE', projectId: 'p6' },
  { id: id(15), title: 'Dark mode theme', status: 'DONE', projectId: 'p6' },
];

const SAMPLE_EDGES: SeedEdge[] = [
  // The big blocker: "Finalize auth token service" holds up 12 tasks.
  [id(1), id(2), 0.95],
  [id(1), id(3), 0.9],
  [id(1), id(4), 0.7],
  // Two small blockers: "Migrate login screen" (4 tasks) and "Session refresh API" (3).
  [id(2), id(5), 0.85],
  [id(2), id(6), 0.6],
  [id(3), id(7), 0.8],
  [id(3), id(8), 0.45],
  [id(4), id(9), 0.9],
  [id(5), id(10), 0.75],
  [id(5), id(11), 0.5],
  [id(7), id(12), 0.65],
  [id(9), id(13), 0.8],
  // Already done: blocks nothing anymore.
  [id(14), id(15), 0.95],
];

/** ~15 tasks: one blocker holding up 12 tasks, two small blockers, and finished tasks. */
export const sampleGraph: DependencyGraph = buildGraph(SAMPLE_TASKS, SAMPLE_EDGES);

/** The sample graph limited to one project's tasks — what `?projectId=` returns. */
export function sampleGraphForProject(projectId: string): DependencyGraph {
  return buildGraph(
    SAMPLE_TASKS.filter((task) => task.projectId === projectId),
    SAMPLE_EDGES,
  );
}

export const emptyGraph: DependencyGraph = { nodes: [], edges: [] };

/** One blocker and the three tasks waiting on it. */
export const singleBlockerGraph: DependencyGraph = buildGraph(
  [
    { id: id(1), title: 'Finalize auth token service', status: 'ACTIVE', projectId: 'p3' },
    { id: id(2), title: 'Migrate login screen', status: 'PENDING', projectId: 'p3' },
    { id: id(3), title: 'Session refresh API', status: 'PENDING', projectId: 'p3' },
    { id: id(4), title: 'SSO for partners', status: 'PENDING', projectId: 'p3' },
  ],
  [
    [id(1), id(2), 0.9],
    [id(1), id(3), 0.8],
    [id(1), id(4), 0.6],
  ],
);

export const longTitlesGraph: DependencyGraph = buildGraph(
  [
    {
      id: id(1),
      title:
        'Replace the legacy authentication token service with the new OAuth 2.1 provider across every customer-facing application',
      status: 'ACTIVE',
      projectId: 'p3',
    },
    {
      id: id(2),
      title:
        'Migrate the login, sign-up and password-reset screens to the new design system components and copy guidelines',
      status: 'PENDING',
      projectId: 'p3',
    },
    {
      id: id(3),
      title: 'Session-refresh-api-with-a-single-very-long-unbroken-identifier-that-has-no-spaces',
      status: 'PENDING',
      projectId: 'p3',
    },
  ],
  [
    [id(1), id(2), 0.9],
    [id(1), id(3), 0.7],
  ],
);

/**
 * A deterministic 200-task graph (10 layers of 20) for the performance story.
 * A tiny seeded generator instead of Math.random(), so every run draws the same graph.
 */
export function buildLargeGraph(layers = 10, perLayer = 20): DependencyGraph {
  let seed = 42;
  const random = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed / 2147483648;
  };
  const statuses: GraphTaskStatus[] = ['PENDING', 'ACTIVE', 'DONE'];
  const largeId = (layer: number, index: number) =>
    `9a1b0c00-0000-4000-8000-${String(layer * perLayer + index).padStart(12, '0')}`;

  const tasks: SeedTask[] = [];
  const edges: SeedEdge[] = [];
  for (let layer = 0; layer < layers; layer++) {
    for (let index = 0; index < perLayer; index++) {
      tasks.push({
        id: largeId(layer, index),
        title: `Task ${layer * perLayer + index + 1}`,
        status: layer === 0 ? statuses[Math.floor(random() * 3)] : 'PENDING',
        projectId: 'p1',
      });
      if (layer > 0) {
        const parents = 1 + Math.floor(random() * 2);
        for (let p = 0; p < parents; p++) {
          const parent = largeId(layer - 1, Math.floor(random() * perLayer));
          if (!edges.some(([source, target]) => source === parent && target === largeId(layer, index))) {
            edges.push([parent, largeId(layer, index), Math.round((0.4 + random() * 0.6) * 100) / 100]);
          }
        }
      }
    }
  }
  return buildGraph(tasks, edges);
}
