'use client';

import CloseIcon from '@mui/icons-material/Close';
import Box from '@mui/material/Box';
import Divider from '@mui/material/Divider';
import Drawer from '@mui/material/Drawer';
import IconButton from '@mui/material/IconButton';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import StatusBadge from '@/components/ui/StatusBadge';
import { TASK_STATUS_BADGE } from '@/components/ui/DependencyGraph';
import type { DependencyGraph, GraphNode } from '@/services/bottlenecksApi';

type TaskDependenciesDrawerProps = {
  /** The task to show; null closes the drawer. */
  task: GraphNode | null;
  graph: DependencyGraph;
  /** Clicking a related task moves the drawer to it. */
  onSelectTask: (id: string) => void;
  onClose: () => void;
};

function TaskList({
  heading,
  tasks,
  emptyText,
  onSelectTask,
}: {
  heading: string;
  tasks: GraphNode[];
  emptyText: string;
  onSelectTask: (id: string) => void;
}) {
  return (
    <Box component="section" aria-label={heading}>
      <Typography variant="subtitle2" component="h3" sx={{ mb: 0.5 }}>
        {heading} ({tasks.length})
      </Typography>
      {tasks.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {emptyText}
        </Typography>
      ) : (
        <List dense disablePadding>
          {tasks.map((related) => (
            <ListItem key={related.id} disablePadding>
              <ListItemButton onClick={() => onSelectTask(related.id)} sx={{ borderRadius: 1 }}>
                <ListItemText
                  primary={related.title}
                  secondary={`${TASK_STATUS_BADGE[related.status].label} · blocks ${related.blocksCount}`}
                />
              </ListItemButton>
            </ListItem>
          ))}
        </List>
      )}
    </Box>
  );
}

/**
 * Details for one task in the dependency graph: the tasks it directly
 * blocks, and the tasks it is directly waiting on.
 */
export default function TaskDependenciesDrawer({ task, graph, onSelectTask, onClose }: TaskDependenciesDrawerProps) {
  const byId = new Map(graph.nodes.map((node) => [node.id, node]));
  const related = (ids: string[]) => ids.map((id) => byId.get(id)).filter((node): node is GraphNode => !!node);
  const blocks = task ? related(graph.edges.filter((e) => e.source === task.id).map((e) => e.target)) : [];
  const waitingOn = task ? related(graph.edges.filter((e) => e.target === task.id).map((e) => e.source)) : [];

  return (
    <Drawer
      anchor="right"
      open={task !== null}
      onClose={onClose}
      slotProps={{ paper: { 'aria-labelledby': 'task-dependencies-title', sx: { width: { xs: '100%', sm: 400 } } } }}
    >
      {task && (
        <Stack spacing={2.5} sx={{ p: 3 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
            <Typography id="task-dependencies-title" variant="h6" component="h2" sx={{ overflowWrap: 'anywhere' }}>
              {task.title}
            </Typography>
            <IconButton aria-label="Close" onClick={onClose} edge="end">
              <CloseIcon />
            </IconButton>
          </Stack>
          <Stack direction="row" spacing={1}>
            <StatusBadge status={TASK_STATUS_BADGE[task.status].tone} label={TASK_STATUS_BADGE[task.status].label} />
            {task.isBlocked && <StatusBadge status="warning" label="Blocked" />}
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {task.blocksCount === 0
              ? 'No other task is waiting on this one.'
              : `${task.blocksCount} ${task.blocksCount === 1 ? 'task is' : 'tasks are'} waiting on this one, directly or indirectly.`}
          </Typography>
          <Divider />
          <TaskList
            heading="Blocks"
            tasks={blocks}
            emptyText="Doesn't directly block any task."
            onSelectTask={onSelectTask}
          />
          <TaskList
            heading="Waiting on"
            tasks={waitingOn}
            emptyText="Not waiting on any task."
            onSelectTask={onSelectTask}
          />
        </Stack>
      )}
    </Drawer>
  );
}
