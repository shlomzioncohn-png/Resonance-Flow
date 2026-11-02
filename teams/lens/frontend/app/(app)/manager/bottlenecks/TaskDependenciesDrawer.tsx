'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import CloseIcon from '@mui/icons-material/Close';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
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
import { ApiError, getErrorMessage } from '@/services/api';
import type { DependencyGraph, GraphNode } from '@/services/bottlenecksApi';
import { getTaskConstraints, updateConstraintStatus } from '@/services/managerApi';
import { bottleneckKeys } from '@/services/queryKeys';
import type { Constraint, ConstraintStatus } from '@/services/workspaceApi';
import { OpenConstraintsChip, softToneSx } from '../../employee/shared';
import {
  constraintStatusLabel,
  constraintTypeLabel,
  isOpenConstraint,
  reasonEntries,
} from '../../employee/tasks/[taskId]/constraintText';

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

/** A pending constraint can be satisfied or waived; a closed one can be reopened. */
function constraintActions(constraint: Constraint): { label: string; status: ConstraintStatus }[] {
  return isOpenConstraint(constraint)
    ? [
        { label: 'Mark satisfied', status: 'SATISFIED' },
        { label: 'Waive', status: 'WAIVED' },
      ]
    : [{ label: 'Reopen', status: 'PENDING' }];
}

/** The generic 404 text talks about "this task" - here it's the constraint that's gone. */
function constraintErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 404) {
    return "This constraint wasn't found. It may have been removed.";
  }
  return getErrorMessage(error);
}

function ConstraintRow({ taskId, constraint }: { taskId: string; constraint: Constraint }) {
  const queryClient = useQueryClient();
  const change = useMutation({
    mutationFn: (status: ConstraintStatus) => updateConstraintStatus(taskId, constraint.constraintId, status),
    onSuccess: (updated) => {
      queryClient.setQueryData<Constraint[]>(bottleneckKeys.constraints(taskId), (constraints) =>
        constraints?.map((c) => (c.constraintId === updated.constraintId ? updated : c)),
      );
      // Whether the task is blocked comes from Chain's graph: refetch every cached project's graph.
      void queryClient.invalidateQueries({ queryKey: bottleneckKeys.graphs() });
    },
  });

  const open = isOpenConstraint(constraint);
  const reason = reasonEntries(constraint.reason);

  return (
    <ListItem divider disableGutters data-open={open} sx={{ display: 'block', py: 1.25 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Chip size="small" label={constraintTypeLabel(constraint.type)} variant={open ? 'filled' : 'outlined'} />
        <Chip
          size="small"
          label={constraintStatusLabel(constraint.status)}
          variant={open ? 'filled' : 'outlined'}
          sx={open ? [softToneSx('error'), { fontWeight: 600 }] : { color: 'text.secondary' }}
        />
      </Stack>
      {reason.length > 0 && (
        <Box component="ul" sx={{ m: 0, mt: 0.75, pl: 2.5, typography: 'body2' }}>
          {reason.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </Box>
      )}
      {constraint.sourceRef && (
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.5, overflowWrap: 'anywhere' }}>
          Source: {constraint.sourceRef}
        </Typography>
      )}
      <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
        {constraintActions(constraint).map((action) => (
          <Button
            key={action.status}
            size="small"
            variant="outlined"
            disabled={change.isPending}
            onClick={() => change.mutate(action.status)}
          >
            {action.label}
          </Button>
        ))}
      </Stack>
      {change.isError && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {constraintErrorMessage(change.error)}
        </Alert>
      )}
    </ListItem>
  );
}

/** The task's constraints from Chain, each with the manager's status actions. */
function ConstraintsSection({ taskId }: { taskId: string }) {
  const constraintsQuery = useQuery({
    queryKey: bottleneckKeys.constraints(taskId),
    queryFn: () => getTaskConstraints(taskId),
  });
  const constraints = constraintsQuery.data;

  return (
    <Box component="section" aria-label="Constraints">
      <Typography variant="subtitle2" component="h3" sx={{ mb: 0.5 }}>
        Constraints{constraints ? ` (${constraints.length})` : ''}
      </Typography>
      {!constraints && constraintsQuery.isError && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => constraintsQuery.refetch()}>
              Try again
            </Button>
          }
        >
          {getErrorMessage(constraintsQuery.error)}
        </Alert>
      )}
      {!constraints && !constraintsQuery.isError && (
        <Stack role="status" direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <CircularProgress size={16} aria-hidden />
          <Typography variant="body2" color="text.secondary">
            Loading constraints…
          </Typography>
        </Stack>
      )}
      {constraints && constraints.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          No constraints on this task
        </Typography>
      )}
      {constraints && constraints.length > 0 && (
        <List dense disablePadding aria-label="Task constraints">
          {constraints.map((constraint) => (
            <ConstraintRow key={constraint.constraintId} taskId={taskId} constraint={constraint} />
          ))}
        </List>
      )}
    </Box>
  );
}

/**
 * The lock chip with the number of open (PENDING) constraints, same as on the
 * employee's screens. Reads the Constraints block's cache entry, so it updates
 * as soon as a row's status changes. Nothing while loading, or with none open.
 */
function OpenConstraintsBadge({ taskId }: { taskId: string }) {
  const { data } = useQuery({
    queryKey: bottleneckKeys.constraints(taskId),
    queryFn: () => getTaskConstraints(taskId),
  });
  return <OpenConstraintsChip count={data?.filter(isOpenConstraint).length} />;
}

/**
 * Details for one task in the dependency graph: the tasks it directly
 * blocks, the tasks it is directly waiting on, and its constraints, which
 * the manager can mark satisfied, waive or reopen.
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
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <StatusBadge status={TASK_STATUS_BADGE[task.status].tone} label={TASK_STATUS_BADGE[task.status].label} />
            {task.isBlocked && <StatusBadge status="warning" label="Blocked" />}
            <OpenConstraintsBadge taskId={task.id} />
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
          {/* Keyed by task, so moving to another task starts with fresh row states. */}
          <ConstraintsSection key={task.id} taskId={task.id} />
        </Stack>
      )}
    </Drawer>
  );
}
