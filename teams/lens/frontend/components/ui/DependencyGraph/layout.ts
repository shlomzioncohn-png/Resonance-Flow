import { Graph, layout } from '@dagrejs/dagre';
import type { DependencyGraph, GraphNode } from '@/services/bottlenecksApi';

export const NODE_WIDTH = 290;
export const NODE_HEIGHT = 110;

/**
 * The `limit` nodes that block the most tasks (ties keep the server's order),
 * and only the edges between them. No limit → the graph as it came.
 */
export function topBlockers(graph: DependencyGraph, limit?: number): DependencyGraph {
  if (limit === undefined || graph.nodes.length <= limit) return graph;
  const nodes = [...graph.nodes].sort((a, b) => b.blocksCount - a.blocksCount).slice(0, limit);
  const kept = new Set(nodes.map((node) => node.id));
  return {
    nodes,
    edges: graph.edges.filter((edge) => kept.has(edge.source) && kept.has(edge.target)),
  };
}

/**
 * Left-to-right layered layout (dagre): every blocker sits left of the tasks
 * it blocks. Returns each node's top-left corner, which is what React Flow
 * positions by (dagre reports centers).
 */
export function layoutGraph(graph: DependencyGraph): Map<string, { x: number; y: number }> {
  const g = new Graph();
  g.setGraph({ rankdir: 'LR', nodesep: 24, ranksep: 72, marginx: 16, marginy: 16 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const node of graph.nodes) g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  for (const edge of graph.edges) g.setEdge(edge.source, edge.target);
  layout(g);

  const positions = new Map<string, { x: number; y: number }>();
  for (const node of graph.nodes) {
    const { x, y } = g.node(node.id);
    positions.set(node.id, { x: x - NODE_WIDTH / 2, y: y - NODE_HEIGHT / 2 });
  }
  return positions;
}

/**
 * The node to move keyboard focus to from `fromId` with an arrow key: the
 * nearest one in that direction, preferring nodes straight ahead over ones
 * off to the side. null when there's nothing that way.
 */
export function nearestInDirection(
  fromId: string,
  direction: 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight',
  positions: Map<string, { x: number; y: number }>,
): string | null {
  const from = positions.get(fromId);
  if (!from) return null;
  let best: string | null = null;
  let bestScore = Infinity;
  for (const [id, to] of positions) {
    if (id === fromId) continue;
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const ahead =
      direction === 'ArrowRight' ? dx : direction === 'ArrowLeft' ? -dx : direction === 'ArrowDown' ? dy : -dy;
    const aside = direction === 'ArrowRight' || direction === 'ArrowLeft' ? Math.abs(dy) : Math.abs(dx);
    if (ahead <= 0) continue;
    const score = ahead + aside * 2;
    if (score < bestScore) {
      bestScore = score;
      best = id;
    }
  }
  return best;
}

/** Sorts a copy of the nodes: most blocking first, then by title. */
export function byBlocksCount(nodes: GraphNode[]): GraphNode[] {
  return [...nodes].sort((a, b) => b.blocksCount - a.blocksCount || a.title.localeCompare(b.title));
}
