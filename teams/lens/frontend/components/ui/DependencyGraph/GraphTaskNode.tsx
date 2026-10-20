'use client';

import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import StatusBadge from '@/components/ui/StatusBadge';
import type { GraphNode } from '@/services/bottlenecksApi';
import { NODE_HEIGHT, NODE_WIDTH } from './layout';
import { blocksLabel, TASK_STATUS_BADGE, type BlockerLevel } from './taskStatus';

export type GraphTaskNodeData = {
  task: GraphNode;
  level: BlockerLevel;
  selected: boolean;
};

export type GraphTaskFlowNode = Node<GraphTaskNodeData, 'task'>;

/** Edges attach here; nobody draws new ones, so the handles stay invisible. */
const hiddenHandle = { opacity: 0, pointerEvents: 'none' } as const;

/**
 * One task in the graph: title, status, and how many tasks it blocks. The
 * wrapping React Flow node carries the aria-label (see DependencyGraph), so
 * the visible content is hidden from screen readers to avoid reading it twice.
 */
function GraphTaskNode({ data }: NodeProps<GraphTaskFlowNode>) {
  const theme = useTheme();
  const { task, level, selected } = data;
  const status = TASK_STATUS_BADGE[task.status];
  const tone = theme.palette.status;
  const borderColor =
    level === 'critical' ? tone.critical.main : level === 'minor' ? tone.warning.main : tone.neutral.main;

  return (
    <>
      <Handle type="target" position={Position.Left} isConnectable={false} style={hiddenHandle} />
      <Box
        aria-hidden="true"
        sx={{
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          p: 1.25,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          bgcolor: level === 'critical' ? tone.critical.bg : 'background.paper',
          border: `${level === 'critical' ? 2 : 1}px solid ${borderColor}`,
          borderRadius: 2,
          boxShadow: selected ? `0 0 0 3px ${theme.palette.primary.main}` : 'none',
          cursor: 'pointer',
        }}
      >
        <Stack direction="row" spacing={0.75} sx={{ alignItems: 'flex-start', minWidth: 0 }}>
          {level === 'critical' && (
            <ReportProblemOutlinedIcon fontSize="small" sx={{ color: tone.critical.main, mt: '1px' }} />
          )}
          <Typography
            variant="body2"
            title={task.title}
            sx={{
              fontWeight: 600,
              lineHeight: 1.3,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              overflowWrap: 'anywhere',
            }}
          >
            {task.title}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
          <Stack direction="row" spacing={0.5}>
            <StatusBadge status={status.tone} label={status.label} />
            {task.isBlocked && <StatusBadge status="warning" label="Blocked" />}
          </Stack>
          <Typography
            variant="caption"
            sx={{
              fontWeight: 600,
              whiteSpace: 'nowrap',
              color: level === 'critical' ? tone.critical.text : 'text.secondary',
            }}
          >
            {blocksLabel(task.blocksCount)}
          </Typography>
        </Stack>
      </Box>
      <Handle type="source" position={Position.Right} isConnectable={false} style={hiddenHandle} />
    </>
  );
}

export default memo(GraphTaskNode);
