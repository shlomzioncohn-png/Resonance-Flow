'use client';

import Box from '@mui/material/Box';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { useDependencyGraph } from '@/hooks/useDependencyGraph';
import type { FlowFlag } from '@/services/flowHealthApi';
import { evidenceNumber } from './flowHealthModel';

type TimelineBarProps = {
  label: string;
  days: number;
  /** The days that fill the whole track. */
  scale: number;
  color: string;
  /** Where the tier median sits on the track, as days; omitted for no marker. */
  marker?: number;
};

function TimelineBar({ label, days, scale, color, marker }: TimelineBarProps) {
  const percent = (value: number) => `${Math.min(100, (value / scale) * 100)}%`;
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}: {Math.round(days)} days
      </Typography>
      <Box sx={{ position: 'relative', height: 14, bgcolor: 'action.hover', borderRadius: 1 }}>
        <Box sx={{ width: percent(days), height: '100%', bgcolor: color, borderRadius: 1 }} />
        {marker !== undefined && (
          <Box
            aria-hidden="true"
            sx={{ position: 'absolute', top: -3, bottom: -3, left: percent(marker), width: 2, bgcolor: 'text.primary' }}
          />
        )}
      </Box>
    </Box>
  );
}

/**
 * Chronic blocker: a timeline of the task (days worked on and days blocked, against
 * the median of its tier) and the tasks it directly blocks, from the dependency graph.
 */
export default function ChronicBlockerDetail({ flag }: { flag: FlowFlag }) {
  const theme = useTheme();
  const graph = useDependencyGraph();
  const taskId = flag.entities[0]?.tasks[0]?.id;

  const daysActive = evidenceNumber(flag.evidence, 'days_active');
  const daysBlocked = evidenceNumber(flag.evidence, 'days_blocked');
  const median = evidenceNumber(flag.evidence, 'tier_median_active_days');
  const dependents = evidenceNumber(flag.evidence, 'n_dependents_max');
  const scale = Math.max(daysActive ?? 0, daysBlocked ?? 0, median ?? 0, 1) * 1.1;

  const titleById = new Map(graph.data?.nodes.map((node) => [node.id, node.title]));
  const blocked = (graph.data?.edges ?? [])
    .filter((edge) => edge.source === taskId)
    .map((edge) => ({ id: edge.target, title: titleById.get(edge.target) ?? edge.target }));

  return (
    <Stack spacing={2.5}>
      <Box component="section" aria-label="Task timeline">
        <Typography variant="subtitle2" component="h3" sx={{ mb: 1 }}>
          Task timeline
        </Typography>
        <Stack spacing={1.5}>
          {daysActive !== undefined && (
            <TimelineBar
              label="Worked on"
              days={daysActive}
              scale={scale}
              color={theme.palette.status.warning.main}
              marker={median}
            />
          )}
          {daysBlocked !== undefined && (
            <TimelineBar label="Blocked" days={daysBlocked} scale={scale} color={theme.palette.status.critical.main} />
          )}
          {median !== undefined && (
            <Typography variant="caption" color="text.secondary">
              The dark line is the median of its tier ({Math.round(median)} days).
            </Typography>
          )}
        </Stack>
      </Box>

      <Box component="section" aria-label="Tasks it blocks">
        <Typography variant="subtitle2" component="h3" sx={{ mb: 0.5 }}>
          Tasks it blocks{graph.data ? ` (${blocked.length})` : ''}
        </Typography>
        {graph.data && blocked.length > 0 ? (
          <List dense disablePadding>
            {blocked.map((task) => (
              <ListItem key={task.id} disablePadding>
                <ListItemText primary={task.title} />
              </ListItem>
            ))}
          </List>
        ) : (
          <Typography variant="body2" color="text.secondary">
            {dependents !== undefined
              ? `Blocked up to ${dependents} ${dependents === 1 ? 'task' : 'tasks'} at once.`
              : 'No blocked tasks to show.'}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}
