import type { StatusTone } from '@/components/ui/StatusBadge';
import type { GraphNode, GraphTaskStatus } from '@/services/bottlenecksApi';

/** How each Chain TaskStatus shows in a StatusBadge: always a word, never color alone. */
export const TASK_STATUS_BADGE: Record<GraphTaskStatus, { tone: StatusTone; label: string }> = {
  PENDING: { tone: 'neutral', label: 'Pending' },
  ACTIVE: { tone: 'healthy', label: 'Active' },
  DONE: { tone: 'neutral', label: 'Done' },
};

/** How strongly a node is highlighted as a blocker. */
export type BlockerLevel = 'critical' | 'minor' | 'none';

export function blockerLevel(node: GraphNode, criticalThreshold: number): BlockerLevel {
  if (node.blocksCount >= criticalThreshold) return 'critical';
  return node.blocksCount > 0 ? 'minor' : 'none';
}

export function blocksLabel(count: number): string {
  return count === 1 ? 'Blocks 1 task' : `Blocks ${count} tasks`;
}

/** Everything the node shows, as one sentence for screen readers. */
export function nodeAriaLabel(node: GraphNode, criticalThreshold: number): string {
  const parts = [node.title, `Status: ${TASK_STATUS_BADGE[node.status].label}`];
  if (node.isBlocked) parts.push('Blocked');
  parts.push(blocksLabel(node.blocksCount));
  if (blockerLevel(node, criticalThreshold) === 'critical') parts.push('Critical blocker');
  return `${parts.join('. ')}.`;
}
