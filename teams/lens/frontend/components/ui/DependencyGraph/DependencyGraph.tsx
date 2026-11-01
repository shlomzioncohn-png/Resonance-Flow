'use client';

import { useMemo, useState, type KeyboardEvent } from 'react';
import {
  Background,
  Controls,
  MarkerType,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Card from '@mui/material/Card';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import EmptyState from '@/components/feedback/EmptyState';
import ErrorState from '@/components/feedback/ErrorState';
import LoadingState from '@/components/feedback/LoadingState';
import DataTable, { type Column } from '@/components/ui/DataTable';
import StatusBadge from '@/components/ui/StatusBadge';
import type { DependencyGraph as Graph, GraphNode } from '@/services/bottlenecksApi';
import GraphTaskNode, { type GraphTaskFlowNode } from './GraphTaskNode';
import OpenConstraintsChip from './OpenConstraintsChip';
import { byBlocksCount, layoutGraph, NODE_HEIGHT, NODE_WIDTH, nearestInDirection, topBlockers } from './layout';
import { blockerLevel, nodeAriaLabel, TASK_STATUS_BADGE } from './taskStatus';

type DependencyGraphProps = {
  graph?: Graph;
  loading?: boolean;
  /** Shown instead of the graph, with a retry button when `onRetry` is given. */
  error?: string | null;
  onRetry?: () => void;
  /** Called with a task id when it's clicked, or chosen with Enter/Space. The caller decides what that opens. */
  onNodeSelect?: (id: string) => void;
  /** Highlighted with a ring, e.g. the task whose details are open. */
  selectedNodeId?: string | null;
  /** A task blocking at least this many tasks is marked critical. */
  criticalThreshold?: number;
  /** "full": the Bottlenecks page, with zoom controls and the table. "compact": a small Dashboard view, graph only. */
  variant?: 'full' | 'compact';
  /** Show only the N tasks blocking the most. Defaults to 5 in "compact", everything in "full". */
  maxNodes?: number;
  /** false: a still picture - no zoom, pan or dragging (clicking a task still works). */
  interactive?: boolean;
  /** Height of the graph area in px. Defaults to 560 ("full") or 280 ("compact"). */
  height?: number;
  ariaLabel?: string;
  emptyTitle?: string;
  emptyDescription?: string;
};

const nodeTypes = { task: GraphTaskNode };

/** Above this, React Flow only renders what's on screen, so big graphs stay smooth. */
const VISIBLE_ONLY_ABOVE = 100;

const ARROW_KEYS = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'] as const;
type ArrowKey = (typeof ARROW_KEYS)[number];

function GraphCanvas({
  graph,
  onNodeSelect,
  selectedNodeId,
  criticalThreshold,
  interactive,
  showControls,
  height,
  ariaLabel,
}: {
  graph: Graph;
  onNodeSelect?: (id: string) => void;
  selectedNodeId?: string | null;
  criticalThreshold: number;
  interactive: boolean;
  showControls: boolean;
  height: number;
  ariaLabel: string;
}) {
  const theme = useTheme();
  const { setCenter, getZoom } = useReactFlow();
  const positions = useMemo(() => layoutGraph(graph), [graph]);
  const titleOf = useMemo(() => new Map(graph.nodes.map((node) => [node.id, node.title])), [graph]);

  const nodes = useMemo<GraphTaskFlowNode[]>(
    () =>
      graph.nodes.map((task) => ({
        id: task.id,
        type: 'task',
        position: positions.get(task.id) ?? { x: 0, y: 0 },
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
        ariaLabel: nodeAriaLabel(task, criticalThreshold),
        data: {
          task,
          level: blockerLevel(task, criticalThreshold),
          selected: task.id === selectedNodeId,
        },
      })),
    [graph, positions, criticalThreshold, selectedNodeId],
  );

  const edges = useMemo<Edge[]>(() => {
    const color = theme.palette.status.neutral.main;
    return graph.edges.map((edge) => ({
      id: `${edge.source}->${edge.target}`,
      source: edge.source,
      target: edge.target,
      ariaLabel: `${titleOf.get(edge.source)} blocks ${titleOf.get(edge.target)}, ${Math.round(edge.confidence * 100)}% confidence`,
      markerEnd: { type: MarkerType.ArrowClosed, color },
      // Less certain dependencies are dashed, so they read as "maybe".
      style: { stroke: color, strokeWidth: 1.5, strokeDasharray: edge.confidence < 0.6 ? '5 4' : undefined },
    }));
  }, [graph, titleOf, theme]);

  const focusNode = (id: string) => {
    const position = positions.get(id);
    if (interactive && position) {
      setCenter(position.x + NODE_WIDTH / 2, position.y + NODE_HEIGHT / 2, { zoom: getZoom() });
    }
    // Next frame: a node that was off screen only exists in the DOM once React Flow re-renders.
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`)?.focus();
    });
  };

  // React Flow makes every node a tab stop; this adds Enter/Space to open one and arrows to move between them.
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const id = (event.target as HTMLElement).closest('.react-flow__node')?.getAttribute('data-id');
    if (!id) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onNodeSelect?.(id);
    } else if ((ARROW_KEYS as readonly string[]).includes(event.key)) {
      event.preventDefault();
      const next = nearestInDirection(id, event.key as ArrowKey, positions);
      if (next) focusNode(next);
    }
  };

  return (
    <Box
      onKeyDown={handleKeyDown}
      sx={{
        height,
        width: '100%',
        // React Flow's own gray attribution link is too faint to read (WCAG contrast).
        '& .react-flow__attribution a': { color: theme.palette.text.secondary },
      }}
    >
      <ReactFlow
        aria-label={ariaLabel}
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={(_, node) => onNodeSelect?.(node.id)}
        fitView
        fitViewOptions={{ padding: 0.15 }}
        minZoom={0.1}
        // Selection is shown through `selectedNodeId`; React Flow's own selection (and moving a
        // selected node with the arrow keys) would fight the arrow-key navigation above.
        elementsSelectable={false}
        nodesConnectable={false}
        edgesFocusable={false}
        nodesDraggable={interactive}
        panOnDrag={interactive}
        zoomOnScroll={interactive}
        zoomOnPinch={interactive}
        zoomOnDoubleClick={interactive}
        preventScrolling={interactive}
        onlyRenderVisibleElements={graph.nodes.length > VISIBLE_ONLY_ABOVE}
      >
        <Background color={theme.palette.status.neutral.bg} gap={16} />
        {showControls && <Controls showInteractive={false} />}
      </ReactFlow>
    </Box>
  );
}

/**
 * Task dependency graph (React Flow, laid out left to right by dagre): every
 * arrow points from a blocker to the task it holds up. A task that blocks
 * `criticalThreshold` or more tasks is marked critical: red border and an icon,
 * so it never relies on color alone. Colors come only from `theme.palette.status`.
 *
 * Keyboard: Tab or the arrow keys move between tasks, Enter/Space selects one.
 * The "full" variant also lists every task in a table below the graph, most
 * blocking first: the same information for screen readers and for anyone who
 * prefers a list.
 */
export default function DependencyGraph({
  graph,
  loading = false,
  error,
  onRetry,
  onNodeSelect,
  selectedNodeId,
  criticalThreshold = 5,
  variant = 'full',
  maxNodes,
  interactive = true,
  height,
  ariaLabel = 'Task dependency graph',
  emptyTitle = 'No dependencies',
  emptyDescription = 'No task is waiting on another task right now.',
}: DependencyGraphProps) {
  const compact = variant === 'compact';
  const limit = maxNodes ?? (compact ? 5 : undefined);
  const visible = useMemo(() => (graph ? topBlockers(graph, limit) : undefined), [graph, limit]);

  if (loading) return <LoadingState message="Loading dependency graph…" />;
  if (error) return <ErrorState description={error} onRetry={onRetry} />;
  if (!visible || visible.nodes.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} />;
  }

  const canvas = (
    <Card variant="outlined" sx={{ overflow: 'hidden' }}>
      <ReactFlowProvider>
        <GraphCanvas
          graph={visible}
          onNodeSelect={onNodeSelect}
          selectedNodeId={selectedNodeId}
          criticalThreshold={criticalThreshold}
          interactive={interactive}
          showControls={!compact && interactive}
          height={height ?? (compact ? 280 : 560)}
          ariaLabel={ariaLabel}
        />
      </ReactFlowProvider>
    </Card>
  );

  if (compact) return canvas;

  return (
    <Stack spacing={2}>
      {canvas}
      <Typography variant="caption" color="text.secondary">
        Arrows point from a task to the tasks waiting on it; dashed arrows are less certain. Use Tab or
        the arrow keys to move between tasks, and Enter to open one.
      </Typography>
      <BlockersTable graph={visible} onNodeSelect={onNodeSelect} criticalThreshold={criticalThreshold} />
    </Stack>
  );
}

const TABLE_PAGE_SIZE = 10;

/** The graph as a list, most blocking first. */
function BlockersTable({
  graph,
  onNodeSelect,
  criticalThreshold,
}: {
  graph: Graph;
  onNodeSelect?: (id: string) => void;
  criticalThreshold: number;
}) {
  const [requestedPage, setPage] = useState(0);
  const rows = useMemo(() => byBlocksCount(graph.nodes), [graph]);
  // A smaller graph (e.g. after filtering) may not have the page we were on.
  const page = Math.min(requestedPage, Math.max(0, Math.ceil(rows.length / TABLE_PAGE_SIZE) - 1));
  const blockedByCount = useMemo(() => {
    const counts = new Map<string, number>();
    for (const edge of graph.edges) counts.set(edge.target, (counts.get(edge.target) ?? 0) + 1);
    return counts;
  }, [graph]);
  const theme = useTheme();

  const columns: Column<GraphNode>[] = [
    {
      id: 'title',
      header: 'Task',
      render: (task) =>
        onNodeSelect ? (
          <Button
            variant="text"
            size="small"
            onClick={() => onNodeSelect(task.id)}
            sx={{ textAlign: 'left', justifyContent: 'flex-start', textTransform: 'none', p: 0, minWidth: 0 }}
          >
            {task.title}
          </Button>
        ) : (
          task.title
        ),
    },
    {
      id: 'status',
      header: 'Status',
      render: (task) => (
        <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
          <StatusBadge status={TASK_STATUS_BADGE[task.status].tone} label={TASK_STATUS_BADGE[task.status].label} />
          {task.isBlocked && <StatusBadge status="warning" label="Blocked" />}
          <OpenConstraintsChip count={task.openConstraintCount} />
        </Stack>
      ),
    },
    {
      id: 'blocksCount',
      header: 'Tasks blocked',
      align: 'right',
      render: (task) =>
        blockerLevel(task, criticalThreshold) === 'critical' ? (
          <Box component="span" sx={{ fontWeight: 700, color: theme.palette.status.critical.text }}>
            {task.blocksCount} (critical)
          </Box>
        ) : (
          task.blocksCount
        ),
    },
    {
      id: 'blockedBy',
      header: 'Waiting on',
      align: 'right',
      render: (task) => blockedByCount.get(task.id) ?? 0,
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={rows.slice(page * TABLE_PAGE_SIZE, (page + 1) * TABLE_PAGE_SIZE)}
      getRowId={(task) => task.id}
      ariaLabel="Tasks by number of tasks blocked"
      pagination={
        rows.length > TABLE_PAGE_SIZE
          ? { page, pageSize: TABLE_PAGE_SIZE, totalCount: rows.length, onPageChange: setPage }
          : undefined
      }
    />
  );
}
